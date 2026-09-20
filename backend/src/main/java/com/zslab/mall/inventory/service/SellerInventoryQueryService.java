package com.zslab.mall.inventory.service;

import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.inventory.controller.response.SellerInventorySummaryResponse;
import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.repository.InventoryRepository;
import com.zslab.mall.inventory.repository.SellerInventoryRepository;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.service.OptionLabelResolver;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 재고 조회 서비스(Track 90-C-1·read-only). 자기 상품의 variant를 행으로 재고 3수치(on_hand·reserved·available)를 돌려준다.
 * 소유 조건은 {@link SellerInventoryRepository}가 {@code product.seller_id = 액터}로 강제한다. 쿼리 수는 count 1 + page 1 +
 * 상품·재고·옵션 라벨 배치(상품 1·재고 1·옵션값 1·그룹 1) = 6으로 고정된다(N+1 없음). 재고 변동 이력 조회는 범위 밖(이월).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerInventoryQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 50;

    private final SellerInventoryRepository sellerInventoryRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final OptionLabelResolver optionLabelResolver;

    /**
     * 셀러 재고 목록. keyword는 상품명 또는 sellerSku 부분일치, productPublicId는 상품 1건 한정(타 셀러·미존재 상품이면 빈 결과).
     *
     * @throws MalformedRequestException keyword가 trim 후 {@value #MAX_KEYWORD_LENGTH}자를 초과할 때(400)
     */
    public PagedResponse<SellerInventorySummaryResponse> listInventories(Long sellerId, String keyword, String productPublicId,
            int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        String normalizedProductPublicId = productPublicId == null || productPublicId.isBlank() ? null : productPublicId.trim();
        Page<ProductVariant> variantPage = sellerInventoryRepository.findOwnedVariants(
                sellerId, normalizedProductPublicId, toLikePattern(normalizeKeyword(keyword)), pageable);
        List<ProductVariant> variants = variantPage.getContent();

        Map<Long, Product> productById = productsByIdFor(variants);
        Map<Long, Inventory> inventoryByVariantId = inventoryByVariantId(variants);
        Map<Long, String> optionLabelByVariantId = optionLabelResolver.resolve(variants);

        List<SellerInventorySummaryResponse> rows = variants.stream()
                .map(variant -> {
                    Product product = productById.get(variant.getProductId());
                    Inventory inventory = inventoryByVariantId.get(variant.getId());
                    return new SellerInventorySummaryResponse(
                            variant.getPublicId(),
                            product != null ? product.getPublicId() : null,
                            product != null ? product.getName() : null,
                            optionLabelByVariantId.get(variant.getId()),
                            variant.getSellerSku(),
                            inventory != null ? inventory.getQuantityOnHand() : 0,
                            inventory != null ? inventory.getQuantityReserved() : 0,
                            inventory != null ? inventory.getQuantityAvailable() : 0,
                            inventory != null ? inventory.getUpdatedAt() : null);
                })
                .toList();
        return PagedResponse.from(new PageImpl<>(rows, pageable, variantPage.getTotalElements()));
    }

    private Map<Long, Product> productsByIdFor(List<ProductVariant> variants) {
        List<Long> productIds = variants.stream().map(ProductVariant::getProductId).distinct().toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productRepository.findByIdIn(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private Map<Long, Inventory> inventoryByVariantId(List<ProductVariant> variants) {
        List<Long> variantIds = variants.stream().map(ProductVariant::getId).toList();
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        return inventoryRepository.findByVariantIdIn(variantIds).stream()
                .collect(Collectors.toMap(Inventory::getVariantId, Function.identity()));
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new MalformedRequestException("keyword는 최대 " + MAX_KEYWORD_LENGTH + "자입니다.");
        }
        return trimmed;
    }

    /** LIKE 와일드카드(%·_)와 escape 문자(\)를 {@code \}로 escape해 리터럴 매칭시킨다(셀러 상품 목록 정합). */
    private static String toLikePattern(String trimmedKeyword) {
        if (trimmedKeyword == null) {
            return null;
        }
        String escaped = trimmedKeyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
