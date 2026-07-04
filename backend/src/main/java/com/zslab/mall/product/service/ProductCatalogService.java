package com.zslab.mall.product.service;

import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.repository.InventoryRepository;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.product.controller.request.ProductCatalogSort;
import com.zslab.mall.product.controller.response.ProductDetailResponse;
import com.zslab.mall.product.controller.response.ProductSummaryResponse;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductImage;
import com.zslab.mall.product.entity.ProductOptionGroup;
import com.zslab.mall.product.entity.ProductOptionValue;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.enums.ProductVariantStatus;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.repository.ProductImageRepository;
import com.zslab.mall.product.repository.ProductOptionGroupRepository;
import com.zslab.mall.product.repository.ProductOptionValueRepository;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.repository.SellerRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
 * 구매자 상품 카탈로그 조회 서비스(Track 44·read-only). 목록·단건 조회와 enrich(내부 BIGINT → public_id·companyName·
 * categoryName)를 담당하며, Controller의 Repository 직접 접근(D-43.11)을 피하는 읽기 전용 계층이다.
 *
 * <p><b>노출·품절·대표가 정책 단일화</b>: 노출대상(D1)은 Repository 쿼리(Product.status=SALE ∧ Seller.status=ACTIVE)가,
 * 대표가(D3)·품절(D2)은 본 서비스의 {@link #displayPrice}·{@link #isPurchasable}가 단일 정책으로 계산한다. 품절 판정은
 * "구매가능(purchasable) = status=SALE ∧ 재고 available&gt;0 ∧ 수동품절 아님"의 부정이며, 상품 단위 soldOut은 판매가능
 * variant가 하나도 구매가능하지 않을 때 true다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProductCatalogService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    // 단순상품 합성 sentinel 옵션 그룹명(ProductRegistrationService의 DEFAULT_OPTION_GROUP_NAME과 동일 계약). 카탈로그 노출에서 숨긴다.
    private static final String DEFAULT_OPTION_GROUP_NAME = "DEFAULT";

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductOptionGroupRepository productOptionGroupRepository;
    private final ProductOptionValueRepository productOptionValueRepository;
    private final ProductImageRepository productImageRepository;
    private final InventoryRepository inventoryRepository;
    private final SellerRepository sellerRepository;
    private final CategoryRepository categoryRepository;

    /** 노출대상 상품 목록(D1 노출·D2 품절·D3 대표가·페이징·정렬). size는 1~100 클램프(BuyerOrderQueryService 정합). */
    public PagedResponse<ProductSummaryResponse> listProducts(
            Long categoryId, ProductCatalogSort sort, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<Product> products = productRepository.findDisplayable(categoryId, sort.name(), pageable);
        List<Product> content = products.getContent();

        List<Long> productIds = content.stream().map(Product::getId).toList();
        Map<Long, List<ProductVariant>> saleVariantsByProduct = saleVariantsByProductId(productIds);
        Map<Long, Inventory> inventoryByVariant = inventoryByVariantId(saleVariantsByProduct.values());
        Map<Long, String> sellerNameById = sellerNamesByIdFor(content);
        Map<Long, Category> categoryById = categoriesByIdFor(content);

        List<ProductSummaryResponse> summaries = content.stream()
                .map(product -> toSummary(
                        product,
                        saleVariantsByProduct.getOrDefault(product.getId(), List.of()),
                        inventoryByVariant,
                        sellerNameById,
                        categoryById))
                .toList();
        Page<ProductSummaryResponse> summaryPage =
                new PageImpl<>(summaries, pageable, products.getTotalElements());
        return PagedResponse.from(summaryPage);
    }

    /** 노출대상 단건 상세. 미존재·비노출(status≠SALE·판매자 비-ACTIVE·삭제)은 전부 404로 은닉한다(§2). */
    public ProductDetailResponse getProduct(String productPublicId) {
        Product product = productRepository.findByPublicId(productPublicId)
                .orElseThrow(() -> new ProductNotFoundException("상품을 찾을 수 없습니다: " + productPublicId));
        if (product.getStatus() != ProductStatus.SALE) {
            throw new ProductNotFoundException("상품을 찾을 수 없습니다: " + productPublicId);
        }
        // 판매자 비-ACTIVE·soft-delete(@SQLRestriction으로 empty)는 모두 404 은닉.
        Seller seller = sellerRepository.findById(product.getSellerId())
                .filter(candidate -> candidate.getStatus() == SellerStatus.ACTIVE)
                .orElseThrow(() -> new ProductNotFoundException("상품을 찾을 수 없습니다: " + productPublicId));

        List<ProductVariant> saleVariants =
                productVariantRepository.findByProductIdAndStatus(product.getId(), ProductVariantStatus.SALE);
        Map<Long, Inventory> inventoryByVariant = inventoryByVariantId(List.of(saleVariants));

        // 옵션 그룹/값 전량 로드 후 DEFAULT sentinel 제외. variant 옵션 라벨 해소를 위해 값→(그룹명·값·DEFAULT여부) 맵을 만든다.
        List<ProductOptionGroup> allGroups = productOptionGroupRepository.findByProductId(product.getId());
        List<Long> groupIds = allGroups.stream().map(ProductOptionGroup::getId).toList();
        List<ProductOptionValue> allValues = groupIds.isEmpty()
                ? List.of()
                : productOptionValueRepository.findByOptionGroupIdIn(groupIds);
        Map<Long, String> groupNameById = allGroups.stream()
                .collect(Collectors.toMap(ProductOptionGroup::getId, ProductOptionGroup::getName));
        Map<Long, ProductOptionValue> valueById = allValues.stream()
                .collect(Collectors.toMap(ProductOptionValue::getId, Function.identity()));

        Category category = categoryRepository.findById(product.getCategoryId()).orElse(null);

        return toDetail(product, seller, category, saleVariants, inventoryByVariant,
                allGroups, allValues, groupNameById, valueById);
    }

    // ==================== 정책 계산(단일화) ====================

    /** 대표가(D3) = basePrice + 판매가능 variant의 MIN(additional_price). 판매가능 variant가 없으면 basePrice. */
    private long displayPrice(Product product, List<ProductVariant> saleVariants) {
        return saleVariants.stream()
                .mapToLong(ProductVariant::getAdditionalPrice)
                .min()
                .orElse(0L) + product.getBasePrice();
    }

    /** 구매가능 여부 = status=SALE ∧ 수동품절 아님 ∧ 재고 available&gt;0(D2 품절식의 부정). */
    private boolean isPurchasable(ProductVariant variant, Map<Long, Inventory> inventoryByVariant) {
        if (variant.getStatus() != ProductVariantStatus.SALE || variant.isSoldoutManual()) {
            return false;
        }
        Inventory inventory = inventoryByVariant.get(variant.getId());
        return inventory != null && inventory.getQuantityAvailable() > 0;
    }

    /** 상품 단위 품절 = 판매가능 variant가 하나도 구매가능하지 않음(없어도 품절·D2). */
    private boolean isProductSoldOut(List<ProductVariant> saleVariants, Map<Long, Inventory> inventoryByVariant) {
        return saleVariants.stream().noneMatch(variant -> isPurchasable(variant, inventoryByVariant));
    }

    // ==================== 매핑 ====================

    private ProductSummaryResponse toSummary(
            Product product,
            List<ProductVariant> saleVariants,
            Map<Long, Inventory> inventoryByVariant,
            Map<Long, String> sellerNameById,
            Map<Long, Category> categoryById) {
        Category category = categoryById.get(product.getCategoryId());
        return new ProductSummaryResponse(
                product.getPublicId(),
                product.getName(),
                product.getThumbnailUrl(),
                displayPrice(product, saleVariants),
                isProductSoldOut(saleVariants, inventoryByVariant),
                product.getCategoryId(),
                category != null ? category.getDisplayName() : null,
                sellerNameById.get(product.getSellerId()));
    }

    private ProductDetailResponse toDetail(
            Product product,
            Seller seller,
            Category category,
            List<ProductVariant> saleVariants,
            Map<Long, Inventory> inventoryByVariant,
            List<ProductOptionGroup> allGroups,
            List<ProductOptionValue> allValues,
            Map<Long, String> groupNameById,
            Map<Long, ProductOptionValue> valueById) {

        List<ProductDetailResponse.Image> images = productImageRepository.findByProductId(product.getId()).stream()
                .sorted(Comparator.comparingInt(ProductImage::getDisplayOrder))
                .map(image -> new ProductDetailResponse.Image(image.getImageUrl(), image.getDisplayOrder(), image.isMain()))
                .toList();

        // 옵션 그룹/값 응답(DEFAULT sentinel 그룹 제외·display_order 오름차순).
        List<ProductDetailResponse.OptionGroup> optionGroups = allGroups.stream()
                .filter(group -> !DEFAULT_OPTION_GROUP_NAME.equals(group.getName()))
                .sorted(Comparator.comparingInt(ProductOptionGroup::getDisplayOrder))
                .map(group -> new ProductDetailResponse.OptionGroup(
                        group.getName(),
                        group.getDisplayOrder(),
                        allValues.stream()
                                .filter(value -> value.getOptionGroup().getId().equals(group.getId()))
                                .sorted(Comparator.comparingInt(ProductOptionValue::getDisplayOrder))
                                .map(value -> new ProductDetailResponse.OptionValue(value.getValue(), value.getDisplayOrder()))
                                .toList()))
                .toList();

        List<ProductDetailResponse.Variant> variants = saleVariants.stream()
                .sorted(Comparator.comparingInt(ProductVariant::getDisplayOrder))
                .map(variant -> new ProductDetailResponse.Variant(
                        variant.getPublicId(),
                        product.getBasePrice() + variant.getAdditionalPrice(),
                        !isPurchasable(variant, inventoryByVariant),
                        variantOptions(variant, groupNameById, valueById)))
                .toList();

        return new ProductDetailResponse(
                product.getPublicId(),
                product.getName(),
                product.getDescription(),
                product.getCategoryId(),
                category != null ? category.getDisplayName() : null,
                seller.getCompanyName(),
                displayPrice(product, saleVariants),
                isProductSoldOut(saleVariants, inventoryByVariant),
                images,
                optionGroups,
                variants);
    }

    /** variant의 option1~3 값을 라벨(그룹명·값)로 해소한다. DEFAULT sentinel 그룹 소속 값은 제외한다(단순상품은 빈 목록). */
    private List<ProductDetailResponse.Option> variantOptions(
            ProductVariant variant, Map<Long, String> groupNameById, Map<Long, ProductOptionValue> valueById) {
        List<Long> valueIds = new ArrayList<>();
        valueIds.add(variant.getOption1ValueId());
        valueIds.add(variant.getOption2ValueId());
        valueIds.add(variant.getOption3ValueId());

        List<ProductDetailResponse.Option> options = new ArrayList<>();
        for (Long valueId : valueIds) {
            if (valueId == null) {
                continue;
            }
            ProductOptionValue value = valueById.get(valueId);
            if (value == null) {
                continue;
            }
            String groupName = groupNameById.get(value.getOptionGroup().getId());
            if (DEFAULT_OPTION_GROUP_NAME.equals(groupName)) {
                continue; // DEFAULT sentinel은 노출하지 않는다.
            }
            options.add(new ProductDetailResponse.Option(groupName, value.getValue()));
        }
        return options;
    }

    // ==================== 배치 조회 helper ====================

    private Map<Long, List<ProductVariant>> saleVariantsByProductId(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productVariantRepository.findByProductIdInAndStatus(productIds, ProductVariantStatus.SALE).stream()
                .collect(Collectors.groupingBy(ProductVariant::getProductId));
    }

    private Map<Long, Inventory> inventoryByVariantId(Collection<List<ProductVariant>> variantGroups) {
        List<Long> variantIds = variantGroups.stream()
                .flatMap(List::stream)
                .map(ProductVariant::getId)
                .toList();
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        return inventoryRepository.findByVariantIdIn(variantIds).stream()
                .collect(Collectors.toMap(Inventory::getVariantId, Function.identity()));
    }

    private Map<Long, String> sellerNamesByIdFor(List<Product> products) {
        List<Long> sellerIds = products.stream().map(Product::getSellerId).distinct().toList();
        if (sellerIds.isEmpty()) {
            return Map.of();
        }
        return sellerRepository.findByIdIn(sellerIds).stream()
                .collect(Collectors.toMap(Seller::getId, Seller::getCompanyName));
    }

    private Map<Long, Category> categoriesByIdFor(List<Product> products) {
        List<Long> categoryIds = products.stream().map(Product::getCategoryId).distinct().toList();
        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        return categoryRepository.findByIdIn(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
    }

    private int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
