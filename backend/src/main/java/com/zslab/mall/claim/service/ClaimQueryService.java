package com.zslab.mall.claim.service;

import com.zslab.mall.claim.controller.response.ClaimResponse;
import com.zslab.mall.claim.controller.response.ClaimSummaryResponse;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemOrderProjection;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.refund.repository.RefundRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구매자 클레임 조회(단건·목록) Application Service. {@link ClaimService}에서 분리했다(D-265).
 */
@Service
@Transactional(readOnly = true)
public class ClaimQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final RefundRepository refundRepository;
    private final DeliveryRepository deliveryRepository;
    private final ClaimAttachmentService claimAttachmentService;
    private final ClaimExchangeService claimExchangeService;
    private final ProductRepository productRepository;
    private final ClaimAccess claimAccess;

    public ClaimQueryService(
            ClaimRepository claimRepository,
            OrderItemRepository orderItemRepository,
            RefundRepository refundRepository,
            DeliveryRepository deliveryRepository,
            ClaimAttachmentService claimAttachmentService,
            ClaimExchangeService claimExchangeService,
            ProductRepository productRepository,
            ClaimAccess claimAccess) {
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.refundRepository = refundRepository;
        this.deliveryRepository = deliveryRepository;
        this.claimAttachmentService = claimAttachmentService;
        this.claimExchangeService = claimExchangeService;
        this.productRepository = productRepository;
        this.claimAccess = claimAccess;
    }

    /**
     * 본인 클레임 단건을 조회한다. 소유권은 주문의 구매자({@code order.buyer_id})로 판정하며(Track 101-B·{@link ClaimAccess#verifyBuyerOwnership}),
     * 미존재·타인 클레임 모두 404다(정보 노출 회피·Q8).
     *
     * @throws ClaimNotFoundException 클레임이 없거나 소유자가 다른 경우
     */
    public ClaimResponse getClaim(String claimPublicId, Long buyerId) {
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: " + claimPublicId));
        claimAccess.verifyBuyerOwnership(claim, buyerId, claimPublicId);
        OrderItem orderItem = orderItemRepository.findById(claim.getOrderItemId())
                .orElseThrow(() -> new IllegalStateException(
                        "클레임의 주문 품목을 찾을 수 없습니다: orderItemId=" + claim.getOrderItemId()));
        String orderItemPublicId = orderItem.getPublicId();
        RefundStatus refundStatus = latestRefundStatusByClaimId(List.of(claim.getId())).get(claim.getId());
        // Track 81-A·FE-29: 클레임 연결 Delivery 1쿼리(id 내림차순) → 회수(RETURN)·검수 불합격 재발송(OUTBOUND) 방향별 최신 1건
        Delivery returnDelivery = null;
        Delivery reshipment = null;
        if (claim.getType().isPickupBased()) {
            for (Delivery delivery : deliveryRepository.findByClaimIdInOrderByIdDesc(List.of(claim.getId()))) {
                if (delivery.getDirection() == DeliveryDirection.RETURN && returnDelivery == null) {
                    returnDelivery = delivery;
                } else if (delivery.getDirection() == DeliveryDirection.OUTBOUND && reshipment == null) {
                    reshipment = delivery;
                }
            }
        }
        // Track 83 D-177: 교환/원 옵션 라벨(EXCHANGE만·배치 1회). 원 옵션은 승인 스냅샷 우선, 없으면(승인 전) 현재 품목 variant로 재조립.
        String exchangeOptionLabel = null;
        String originalOptionLabel = null;
        if (claim.getType() == ClaimType.EXCHANGE) {
            Long originalVariantId = claim.getOriginalVariantId() != null ? claim.getOriginalVariantId()
                    : orderItemRepository.findById(claim.getOrderItemId()).map(OrderItem::getVariantId).orElse(null);
            Set<Long> variantIds = new java.util.LinkedHashSet<>();
            variantIds.add(claim.getExchangeVariantId());
            if (originalVariantId != null) {
                variantIds.add(originalVariantId);
            }
            Map<Long, String> labels = claimExchangeService.optionLabelsByVariantId(variantIds);
            exchangeOptionLabel = labels.get(claim.getExchangeVariantId());
            originalOptionLabel = claim.getOriginalOptionLabel() != null ? claim.getOriginalOptionLabel()
                    : (originalVariantId == null ? null : labels.get(originalVariantId));
        }
        // Track 81-B: 첨부 URL 1쿼리(순서 보존·없으면 빈 목록)
        return ClaimResponse.from(claim, orderItemPublicId, refundStatus, returnDelivery,
                claimAttachmentService.urlsOf(claim.getId()), reshipment, exchangeOptionLabel, originalOptionLabel,
                orderContextOf(claim, orderItem));
    }

    /**
     * 구매자 상세의 주문·대상 품목(Track 105-4g-3). 주문은 목록과 같은 스칼라 projection 1쿼리({@link #listClaims} 주석),
     * 썸네일은 목록과 같은 {@link #thumbnailUrlByProductId} 1쿼리 —
     * 고정 2쿼리. 상품명·옵션·수량은 이미 적재한 품목 스냅샷이라 추가 조회가 없다.
     */
    private ClaimResponse.OrderContext orderContextOf(Claim claim, OrderItem orderItem) {
        List<OrderItemOrderProjection> orders = orderItemRepository.findOrderSummariesByIdIn(List.of(orderItem.getId()));
        OrderItemOrderProjection order = orders.isEmpty() ? null : orders.get(0);
        String thumbnailUrl = order == null ? null : thumbnailUrlByProductId(orders).get(order.getProductId());
        String optionLabel = claim.getOriginalOptionLabel() != null ? claim.getOriginalOptionLabel() : orderItem.getOptionLabel();
        return new ClaimResponse.OrderContext(
                order == null ? null : order.getOrderPublicId(),
                order == null ? null : order.getOrderNo(),
                new ClaimResponse.ClaimItemSummary(orderItem.getProductName(), optionLabel, orderItem.getQuantity(), thumbnailUrl));
    }

    /**
     * 본인 클레임 목록(Track 101-B·주문 구매자 기준·D-54 페이징·요청 시각 내림차순). size는 1~100 클램프이며 {@code type}이 null이면
     * 전체 유형이다(주문내역 탭의 취소·반품·교환 탭이 유형을 건다).
     *
     * <p>enrich는 전부 페이지 단위 배치다 — 환불 상태 1쿼리(Track 80) + 주문·품목 요약 projection 1쿼리(관리자 목록
     * {@code AdminClaimQueryService} 선례). 주문번호와 상품명을 같은 projection에서 읽으므로 품목 엔티티를 따로 적재하지 않는다
     * (외부 검토 반영·이전에는 같은 itemIds로 {@code findAllById}가 한 번 더 나갔다). 주문도 엔티티로 적재하지 않고 같은
     * projection에서 읽는다.
     */
    public PagedResponse<ClaimSummaryResponse> listClaims(Long buyerId, ClaimType type, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<Claim> claimPage = type == null
                ? claimRepository.findAllByOrderBuyerId(buyerId, pageable)
                : claimRepository.findAllByOrderBuyerIdAndType(buyerId, type, pageable);
        Map<Long, RefundStatus> refundStatusByClaimId = latestRefundStatusByClaimId(
                claimPage.getContent().stream().map(Claim::getId).toList());

        List<Long> itemIds = claimPage.getContent().stream().map(Claim::getOrderItemId).distinct().toList();
        Map<Long, OrderItemOrderProjection> orderByItemId = itemIds.isEmpty()
                ? Map.of()
                : orderItemRepository.findOrderSummariesByIdIn(itemIds).stream()
                        .collect(Collectors.toMap(OrderItemOrderProjection::getOrderItemId, Function.identity()));
        Map<Long, String> thumbnailUrlByProductId = thumbnailUrlByProductId(orderByItemId.values());

        Page<ClaimSummaryResponse> claims = claimPage.map(claim -> {
            OrderItemOrderProjection order = orderByItemId.get(claim.getOrderItemId());
            return ClaimSummaryResponse.from(
                    claim,
                    refundStatusByClaimId.get(claim.getId()),
                    order == null ? null : order.getOrderNo(),
                    order == null ? null : order.getProductName(),
                    order == null ? null : thumbnailUrlByProductId.get(order.getProductId()));
        });
        return PagedResponse.from(claims);
    }

    /**
     * 상품별 썸네일(Track 105-4b·D-223 주문 목록과 같은 {@code product.thumbnail_url}·같은 {@code findByIdIn} 배치). 페이지 상품
     * IN 1쿼리이며, 삭제 상품(@SQLRestriction)·썸네일 미등록은 키가 없다. 빈 입력은 0쿼리.
     */
    private Map<Long, String> thumbnailUrlByProductId(Collection<OrderItemOrderProjection> orders) {
        List<Long> productIds = orders.stream().map(OrderItemOrderProjection::getProductId).distinct().toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productRepository.findByIdIn(productIds).stream()
                .filter(product -> product.getThumbnailUrl() != null)
                .collect(Collectors.toMap(Product::getId, Product::getThumbnailUrl));
    }

    /** 클레임별 최신 환불 상태(id 내림차순 조회·first-wins). 환불 미생성 클레임은 키가 없다. 빈 입력은 0쿼리. */
    private Map<Long, RefundStatus> latestRefundStatusByClaimId(List<Long> claimIds) {
        if (claimIds.isEmpty()) {
            return Map.of();
        }
        return refundRepository.findByClaimIdInOrderByIdDesc(claimIds).stream()
                .collect(Collectors.toMap(Refund::getClaimId, Refund::getStatus, (latest, older) -> latest));
    }

    private int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
