package com.zslab.mall.claim.service;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.policy.ProductPurchasePolicy;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import com.zslab.mall.product.service.OptionLabelResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 교환 클레임 전용 Application Service(Track 83 D-177). 반품 흐름(신청·승인·회수·검수)을 재사용하는 {@link ClaimService}가 교환에서만
 * 필요한 단계 — 교환 옵션 검증(결정 9)·승인 시 재고 예약(결정 8·10)·거부/검수 FAIL 시 예약 해제·교환품 배송완료 종결(결정 1·2) — 를 본
 * 서비스에 위임한다. 차액 환불(D-115)은 같은 가격 옵션만 허용하므로 폐기됐다.
 *
 * <p><b>락 순서(D-172 보충)</b>: Claim → OrderItem → Inventory(항상 마지막). 예약·확정·해제는 클레임 행 락 아래에서만 호출하며
 * {@code exchange_reserved_at}이 예약 사실의 SoT다(예약은 inventory_history를 남기지 않는다).
 */
@Slf4j
@Service
@Transactional
public class ClaimExchangeService {

    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final InventoryService inventoryService;
    private final OptionLabelResolver optionLabelResolver;
    private final TracedEventPublisher eventPublisher;
    private final EntityManager entityManager;

    public ClaimExchangeService(ClaimRepository claimRepository, OrderItemRepository orderItemRepository,
            ProductRepository productRepository, ProductVariantRepository productVariantRepository,
            InventoryService inventoryService, OptionLabelResolver optionLabelResolver, TracedEventPublisher eventPublisher,
            EntityManager entityManager) {
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.productVariantRepository = productVariantRepository;
        this.inventoryService = inventoryService;
        this.optionLabelResolver = optionLabelResolver;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
    }

    /**
     * 요청 본문의 교환 옵션 public id(var_)를 내부 id로 해소한다. EXCHANGE는 필수, 그 외 유형은 지정 불가.
     *
     * @return EXCHANGE면 variant id, 그 외 null
     * @throws MalformedRequestException EXCHANGE인데 미지정·미존재 / 비교환인데 지정(400)
     */
    @Transactional(readOnly = true)
    public Long resolveRequestedVariantId(ClaimType claimType, String exchangeVariantPublicId) {
        boolean given = exchangeVariantPublicId != null && !exchangeVariantPublicId.isBlank();
        if (claimType != ClaimType.EXCHANGE) {
            if (given) {
                throw new MalformedRequestException("교환 옵션(exchangeVariantId)은 교환 요청에서만 지정할 수 있습니다.");
            }
            return null;
        }
        if (!given) {
            throw new MalformedRequestException("교환 요청은 교환 옵션(exchangeVariantId)이 필수입니다.");
        }
        return productVariantRepository.findByPublicId(exchangeVariantPublicId)
                .map(ProductVariant::getId)
                .orElseThrow(() -> new MalformedRequestException("교환 옵션을 찾을 수 없습니다: " + exchangeVariantPublicId));
    }

    /**
     * 교환 요청 조건(D-177 결정 9·11). 재교환 차단(같은 품목에 EXCHANGE·COMPLETED 존재) 후 옵션 규칙을 검증한다.
     *
     * @throws ClaimInvalidStateException 재교환·같은 상품 아님·같은 옵션·가격 다름·판매 불가(422)
     */
    @Transactional(readOnly = true)
    public void validateExchangeRequest(OrderItem orderItem, Long exchangeVariantId, LocalDateTime now) {
        if (claimRepository.existsByOrderItemIdAndTypeAndStatus(orderItem.getId(), ClaimType.EXCHANGE, ClaimStatus.COMPLETED)) {
            throw new ClaimInvalidStateException("이미 교환이 완료된 품목은 다시 교환할 수 없습니다(반품은 가능): orderItemId=" + orderItem.getId());
        }
        validateExchangeOption(orderItem, exchangeVariantId, now);
    }

    /**
     * 승인 TX에서 교환 옵션을 재검증하고 재고를 예약한다(D-177 결정 8·9·10). 호출부({@code ClaimService.approve})가 클레임 행을 이미
     * 잠근 상태여야 한다. 이미 예약돼 있으면 no-op(멱등·재예약 금지). Inventory 락은 마지막에 잡는다.
     *
     * @throws ClaimInvalidStateException 옵션 부적합(422)
     * @throws com.zslab.mall.inventory.exception.InventoryInvariantViolationException 교환 옵션 재고 부족(422·TX 롤백)
     */
    public void reserveExchangeStock(Claim claim, LocalDateTime now) {
        if (claim.isExchangeReserved()) {
            log.info("[ClaimExchange] 재고 예약 멱등 NO-OP(이미 예약됨): claimId={}", claim.getId());
            return;
        }
        OrderItem orderItem = orderItemRepository.findById(claim.getOrderItemId())
                .orElseThrow(() -> new IllegalStateException("OrderItem 무결성 위반: orderItemId=" + claim.getOrderItemId()));
        validateExchangeOption(orderItem, claim.getExchangeVariantId(), now);
        inventoryService.reserve(claim.getExchangeVariantId(), orderItem.getQuantity());
        claim.markExchangeReserved(orderItem.getVariantId(), orderItem.getOptionLabel(), now);
        log.info("[ClaimExchange] 교환 옵션 재고 예약 claimId={} variantId={} qty={}", claim.getId(), claim.getExchangeVariantId(),
                orderItem.getQuantity());
    }

    /** 예약이 있으면 해제하고 표식을 지운다(거부·검수 FAIL·D-177 결정 8). 미예약·이미 해제는 no-op(멱등·중복 해제 금지). */
    public void releaseExchangeReservationIfAny(Claim claim) {
        if (claim.getType() != ClaimType.EXCHANGE || !claim.isExchangeReserved()) {
            return;
        }
        OrderItem orderItem = orderItemRepository.findById(claim.getOrderItemId())
                .orElseThrow(() -> new IllegalStateException("OrderItem 무결성 위반: orderItemId=" + claim.getOrderItemId()));
        inventoryService.release(claim.getExchangeVariantId(), orderItem.getQuantity());
        claim.clearExchangeReserved();
        log.info("[ClaimExchange] 교환 옵션 재고 예약 해제 claimId={} variantId={} qty={}", claim.getId(),
                claim.getExchangeVariantId(), orderItem.getQuantity());
    }

    /**
     * 교환품 배송완료 종결(D-177 결정 1·2·4·8). 클레임 행을 잠근 뒤 (1) 예약분 확정 + 회수품 재입고(restock·원 옵션) (2) 품목 variant·옵션
     * 라벨을 교환 옵션으로 갱신 (3) Claim COMPLETED → {@link ClaimCompleted} 발행(소비처가 품목을 EXCHANGE_REQUESTED → DELIVERED로 복귀·
     * 재고 핸들러는 이력 존재로 skip). 이미 COMPLETED면 멱등 no-op. 호출처 {@code ExchangeDeliveryCompletedHandler}와 같은 TX.
     *
     * @throws ClaimNotFoundException     클레임 없음
     * @throws ClaimInvalidStateException type != EXCHANGE·APPROVED 아님·검수 PASS 아님·재고 예약 없음(422)
     */
    public void completeExchange(Long claimId) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
        entityManager.refresh(claim, LockModeType.PESSIMISTIC_WRITE);
        if (claim.getStatus() == ClaimStatus.COMPLETED) {
            log.info("[ClaimExchange] completeExchange 멱등 NO-OP(이미 COMPLETED): claimId={}", claimId);
            return;
        }
        if (claim.getType() != ClaimType.EXCHANGE) {
            throw new ClaimInvalidStateException("교환 종결은 EXCHANGE 클레임에서만 가능합니다: type=" + claim.getType());
        }
        if (claim.getStatus() != ClaimStatus.APPROVED || !claim.isInspectionPassed()) {
            throw new ClaimInvalidStateException("검수 합격한 승인 교환만 종결할 수 있습니다: status=" + claim.getStatus()
                    + " inspection=" + claim.getInspectionResult());
        }
        if (!claim.isExchangeReserved()) {
            throw new ClaimInvalidStateException("교환 옵션 재고 예약이 없어 종결할 수 없습니다: claimId=" + claimId);
        }
        OrderItem orderItem = orderItemRepository.findById(claim.getOrderItemId())
                .orElseThrow(() -> new IllegalStateException("OrderItem 무결성 위반: orderItemId=" + claim.getOrderItemId()));
        ProductVariant exchangeVariant = productVariantRepository.findById(claim.getExchangeVariantId())
                .orElseThrow(() -> new IllegalStateException("교환 옵션 무결성 위반: variantId=" + claim.getExchangeVariantId()));

        // (1) 재고: 예약 확정(교환 옵션) + 회수품 재입고(원 옵션·restock) — Inventory 최후·variant 오름차순(InventoryService 내부)
        inventoryService.commitExchange(claim.getExchangeVariantId(), claim.getOriginalVariantId(), orderItem.getQuantity(),
                claim.isRestockRequested(), claimId);
        claim.clearExchangeReserved();

        // (2) 품목을 교환 옵션으로 갱신(결정 2 γ). 라벨은 주문 시점과 같은 OptionLabelResolver로 조립한다.
        String optionLabel = optionLabelResolver.resolve(List.of(exchangeVariant)).get(exchangeVariant.getId());
        orderItem.applyExchange(exchangeVariant.getId(), optionLabel);

        // (3) 종결·이벤트(D-29 save→publish). ClaimCompletedHandler가 품목을 DELIVERED로 복귀시킨다(결정 1 α).
        claim.markCompleted(LocalDateTime.now());
        claimRepository.save(claim);
        eventPublisher.publishEvent(new ClaimCompleted(
                claim.getId(), claim.getPublicId(), claim.getOrderItemId(),
                claim.getType(), claim.getStatus(), LocalDateTime.now()));
        log.info("[ClaimExchange] 교환 종결 claimId={} orderItemId={} {} → {}", claimId, orderItem.getId(),
                claim.getOriginalVariantId(), exchangeVariant.getId());
    }

    /** 클레임 목록·상세 응답용 variant id → 옵션 라벨 배치 조회(없거나 미해소는 항목 없음). */
    @Transactional(readOnly = true)
    public Map<Long, String> optionLabelsByVariantId(Collection<Long> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        return optionLabelResolver.resolve(productVariantRepository.findByIdIn(variantIds));
    }

    /**
     * 교환 옵션 규칙(D-177 결정 9): 원 품목과 같은 상품·다른 옵션·같은 판매가(base + additional)·판매 가능(상품 판매중·variant SALE·
     * 수동 품절 아님·{@link ProductPurchasePolicy#saleBlock}).
     */
    private void validateExchangeOption(OrderItem orderItem, Long exchangeVariantId, LocalDateTime now) {
        if (exchangeVariantId == null) {
            throw new ClaimInvalidStateException("교환 옵션이 지정되지 않았습니다: orderItemId=" + orderItem.getId());
        }
        if (exchangeVariantId.equals(orderItem.getVariantId())) {
            throw new ClaimInvalidStateException("같은 옵션으로는 교환할 수 없습니다: variantId=" + exchangeVariantId);
        }
        ProductVariant exchangeVariant = productVariantRepository.findById(exchangeVariantId)
                .orElseThrow(() -> new ClaimInvalidStateException("교환 옵션을 찾을 수 없습니다: variantId=" + exchangeVariantId));
        if (!exchangeVariant.getProductId().equals(orderItem.getProductId())) {
            throw new ClaimInvalidStateException("같은 상품의 옵션으로만 교환할 수 있습니다: productId=" + orderItem.getProductId()
                    + " variantProductId=" + exchangeVariant.getProductId());
        }
        Product product = productRepository.findById(orderItem.getProductId())
                .orElseThrow(() -> new ClaimInvalidStateException("상품을 찾을 수 없습니다: productId=" + orderItem.getProductId()));
        long exchangeUnitPrice = product.getBasePrice() + exchangeVariant.getAdditionalPrice();
        if (exchangeUnitPrice != orderItem.getUnitPrice()) {
            throw new ClaimInvalidStateException("같은 가격의 옵션으로만 교환할 수 있습니다: 주문 단가=" + orderItem.getUnitPrice()
                    + " 교환 옵션 단가=" + exchangeUnitPrice);
        }
        ProductPurchasePolicy.saleBlock(product, exchangeVariant, now).ifPresent(reason -> {
            throw new ClaimInvalidStateException("판매 중이 아닌 옵션으로는 교환할 수 없습니다(" + reason + "): variantId="
                    + exchangeVariantId);
        });
    }
}
