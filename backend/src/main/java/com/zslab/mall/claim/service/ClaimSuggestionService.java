package com.zslab.mall.claim.service;

import com.zslab.mall.attachment.repository.AttachmentCountProjection;
import com.zslab.mall.attachment.repository.AttachmentRepository;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.entity.ClaimSuggestionRecord;
import com.zslab.mall.claim.enums.ClaimDecision;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.repository.ClaimSuggestionRecordRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.inventory.repository.InventoryAvailableProjection;
import com.zslab.mall.inventory.repository.InventoryRepository;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.policy.ProductPurchasePolicy;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 클레임 처리 제안(D-250). 규칙 입력을 배치로 모아({@link ClaimSuggestionPolicy}에 넘기고) 클레임별 결과를 돌려준다.
 * 트랜잭션은 호출자 것을 따른다(단건 조회·인박스 수집 = 읽기 전용 · 관리자 승인·거부 = 전이 트랜잭션 안 재계산).
 *
 * <p>쿼리 수는 클레임 수와 무관하다: 품목 1 · 첨부 개수 1 · (교환이 있으면) 옵션·상품·재고 각 1. 재고는 값으로만 읽는다(엔티티 미적재).
 */
@Service
public class ClaimSuggestionService {

    private final ClaimSuggestionPolicy policy;
    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final AttachmentRepository attachmentRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final ClaimSuggestionRecordRepository recordRepository;
    private final ObjectMapper objectMapper;

    public ClaimSuggestionService(ClaimSuggestionPolicy policy, ClaimRepository claimRepository,
            OrderItemRepository orderItemRepository, AttachmentRepository attachmentRepository,
            ProductVariantRepository productVariantRepository, ProductRepository productRepository,
            InventoryRepository inventoryRepository, ClaimSuggestionRecordRepository recordRepository,
            ObjectMapper objectMapper) {
        this.policy = policy;
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.attachmentRepository = attachmentRepository;
        this.productVariantRepository = productVariantRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.recordRepository = recordRepository;
        this.objectMapper = objectMapper;
    }

    public ClaimSuggestionResult suggest(Claim claim) {
        return suggestAll(List.of(claim)).get(claim.getId());
    }

    /**
     * 관리자 승인·거부 시점의 제안과 결정을 1행 남긴다(D-220 보완 · 호출자 트랜잭션에 참여 — 전이가 롤백되면 기록도 없다).
     *
     * @param suggestion 전이 전 상태로 계산한 제안
     */
    public void record(Long claimId, ClaimSuggestionResult suggestion, ClaimDecision decision, Long decidedBy,
            LocalDateTime decidedAt) {
        recordRepository.save(ClaimSuggestionRecord.of(claimId, suggestion.rule(), toJson(suggestion.input()), decision,
                decidedBy, decidedAt));
    }

    private String toJson(ClaimSuggestionInput input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JacksonException exception) {
            // 입력은 enum·숫자·문자열뿐인 record라 직렬화 실패는 코드 결함이다 — 기록 없이 결정만 남기지 않도록 전이째 롤백한다.
            throw new IllegalStateException("제안 입력 스냅샷 직렬화 실패: " + input, exception);
        }
    }

    /** 인박스 클레임 접수 행용 — publicId별 결과. 이미 처리돼 REQUESTED가 아닌 클레임은 빠진다. */
    public Map<String, ClaimSuggestionResult> suggestRequestedByPublicIds(Collection<String> publicIds) {
        if (publicIds.isEmpty()) {
            return Map.of();
        }
        List<Claim> claims = claimRepository.findByPublicIdIn(publicIds).stream()
                .filter(claim -> claim.getStatus() == ClaimStatus.REQUESTED)
                .toList();
        Map<Long, ClaimSuggestionResult> resultById = suggestAll(claims);
        Map<String, ClaimSuggestionResult> resultByPublicId = new HashMap<>();
        for (Claim claim : claims) {
            resultByPublicId.put(claim.getPublicId(), resultById.get(claim.getId()));
        }
        return resultByPublicId;
    }

    /** 클레임 id별 결과. */
    public Map<Long, ClaimSuggestionResult> suggestAll(Collection<Claim> claims) {
        if (claims.isEmpty()) {
            return Map.of();
        }
        Set<Long> itemIds = claims.stream().map(Claim::getOrderItemId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<Long> claimIds = claims.stream().map(Claim::getId).toList();
        Map<Long, OrderItem> itemById = orderItemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        Map<Long, Long> attachmentCountByClaimId = attachmentRepository
                .countByTargetTypeAndTargetIdIn(PolymorphicTargetType.CLAIM, claimIds).stream()
                .collect(Collectors.toMap(AttachmentCountProjection::getTargetId, AttachmentCountProjection::getAttachmentCount));
        ExchangeOptions exchangeOptions = loadExchangeOptions(claims);
        LocalDateTime now = LocalDateTime.now();

        Map<Long, ClaimSuggestionResult> resultById = new HashMap<>();
        for (Claim claim : claims) {
            OrderItem item = itemById.get(claim.getOrderItemId());
            ClaimSuggestionInput input = new ClaimSuggestionInput(
                    claim.getType(),
                    claim.getReasonCode(),
                    attachmentCountByClaimId.getOrDefault(claim.getId(), 0L),
                    claim.getPreviousOrderItemStatus(),
                    item == null ? 0 : item.getQuantity(),
                    claim.getType() == ClaimType.EXCHANGE ? exchangeOptions.onSale(claim.getExchangeVariantId(), now) : null,
                    claim.getType() == ClaimType.EXCHANGE ? exchangeOptions.available(claim.getExchangeVariantId()) : null);
            resultById.put(claim.getId(), new ClaimSuggestionResult(policy.evaluate(input), input));
        }
        return resultById;
    }

    private ExchangeOptions loadExchangeOptions(Collection<Claim> claims) {
        Set<Long> variantIds = claims.stream()
                .filter(claim -> claim.getType() == ClaimType.EXCHANGE && claim.getExchangeVariantId() != null)
                .map(Claim::getExchangeVariantId)
                .collect(Collectors.toSet());
        if (variantIds.isEmpty()) {
            return new ExchangeOptions(Map.of(), Map.of(), Map.of());
        }
        Map<Long, ProductVariant> variantById = productVariantRepository.findByIdIn(variantIds).stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
        Set<Long> productIds = variantById.values().stream().map(ProductVariant::getProductId).collect(Collectors.toSet());
        Map<Long, Product> productById = productIds.isEmpty()
                ? Map.of()
                : productRepository.findByIdIn(productIds).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        // 재고는 값으로만 읽는다 — 엔티티로 올리면 승인 트랜잭션의 교환 재고 예약(잠금 조회)이 캐시된 옛 예약 수로 덮어쓴다.
        Map<Long, Integer> availableByVariantId = inventoryRepository.findAvailableByVariantIdIn(variantIds).stream()
                .collect(Collectors.toMap(InventoryAvailableProjection::getVariantId, InventoryAvailableProjection::getQuantityAvailable));
        return new ExchangeOptions(variantById, productById, availableByVariantId);
    }

    private record ExchangeOptions(Map<Long, ProductVariant> variantById, Map<Long, Product> productById,
            Map<Long, Integer> availableByVariantId) {

        /** 승인 시 재검증({@code ClaimExchangeService.validateExchangeOption})과 같은 판매 판정. 옵션·상품이 없으면 판매 중 아님. */
        boolean onSale(Long variantId, LocalDateTime now) {
            ProductVariant variant = variantById.get(variantId);
            Product product = variant == null ? null : productById.get(variant.getProductId());
            return product != null && ProductPurchasePolicy.saleBlock(product, variant, now).isEmpty();
        }

        /** 재고 행이 없으면 0(구매 판정과 같이 품절로 본다). */
        int available(Long variantId) {
            return availableByVariantId.getOrDefault(variantId, 0);
        }
    }
}
