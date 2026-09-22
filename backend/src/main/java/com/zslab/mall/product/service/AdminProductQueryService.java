package com.zslab.mall.product.service;

import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.repository.InventoryRepository;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.product.controller.request.AdminProductSort;
import com.zslab.mall.product.controller.request.AdminProductStockFilter;
import com.zslab.mall.product.controller.response.AdminProductDetailResponse;
import com.zslab.mall.product.controller.response.AdminProductSummaryResponse;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductImage;
import com.zslab.mall.product.entity.ProductOptionGroup;
import com.zslab.mall.product.entity.ProductOptionValue;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.policy.ProductPurchasePolicy;
import com.zslab.mall.product.repository.AdminProductSpecifications;
import com.zslab.mall.product.repository.ProductImageRepository;
import com.zslab.mall.product.repository.ProductOptionGroupRepository;
import com.zslab.mall.product.repository.ProductOptionValueRepository;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.exception.SellerNotFoundException;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 상품 조회 서비스(Track 76·read-only). 목록(필터·정렬·페이징)과 수정 화면용 상세를 담당한다. 노출 필터가 없다는 점
 * (모든 상태·판매기간 밖 포함·soft-delete만 제외)과 내부 id(이미지·옵션) 노출이 {@link ProductCatalogService}와 다르다.
 *
 * <p>목록은 상품 페이지 1쿼리 + variant·inventory·seller·category 배치 4쿼리로 고정된다(N+1 없음·통합 테스트가 쿼리 수 검증).
 * 품절 표기는 {@link ProductPurchasePolicy#isSoldOut}(단일 정책)이며 품절 필터(Specification)도 같은 정의를 SQL로 옮긴 것이다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminProductQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_KEYWORD_LENGTH = 50;

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductOptionGroupRepository productOptionGroupRepository;
    private final ProductOptionValueRepository productOptionValueRepository;
    private final ProductImageRepository productImageRepository;
    private final InventoryRepository inventoryRepository;
    private final SellerRepository sellerRepository;
    private final CategoryRepository categoryRepository;

    /**
     * 관리자 상품 목록. keyword는 상품명 부분일치 또는 public_id 정확일치, sellerPublicId는 slr_ 외부키(미존재 404).
     *
     * @throws MalformedRequestException keyword가 trim 후 {@value #MAX_KEYWORD_LENGTH}자를 초과할 때(400)
     * @throws SellerNotFoundException sellerPublicId에 해당하는 셀러가 없을 때(404)
     */
    public PagedResponse<AdminProductSummaryResponse> listProducts(
            String keyword, ProductStatus status, Boolean soldOut, String sellerPublicId, Long categoryId,
            AdminProductStockFilter stockFilter, AdminProductSort sort, int page, int size) {
        Long sellerId = resolveSellerId(sellerPublicId);
        String trimmedKeyword = normalizeKeyword(keyword);
        Specification<Product> specification = Specification
                .where(AdminProductSpecifications.keyword(toLikePattern(trimmedKeyword), trimmedKeyword))
                .and(AdminProductSpecifications.status(status))
                .and(AdminProductSpecifications.soldOut(soldOut))
                .and(AdminProductSpecifications.sellerId(sellerId))
                .and(AdminProductSpecifications.categoryId(categoryId))
                .and(AdminProductSpecifications.stockFilter(stockFilter));
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), toSort(sort));
        Page<Product> products = productRepository.findAll(specification, pageable);
        List<Product> content = products.getContent();

        List<Long> productIds = content.stream().map(Product::getId).toList();
        Map<Long, List<ProductVariant>> variantsByProduct = variantsByProductId(productIds);
        Map<Long, Inventory> inventoryByVariant = inventoryByVariantId(variantsByProduct.values());
        Map<Long, Seller> sellerById = sellersByIdFor(content);
        Map<Long, Category> categoryById = categoriesByIdFor(content);

        List<AdminProductSummaryResponse> rows = content.stream()
                .map(product -> toSummary(
                        product,
                        variantsByProduct.getOrDefault(product.getId(), List.of()),
                        inventoryByVariant,
                        sellerById.get(product.getSellerId()),
                        categoryById.get(product.getCategoryId())))
                .toList();
        Page<AdminProductSummaryResponse> rowPage = new PageImpl<>(rows, pageable, products.getTotalElements());
        return PagedResponse.from(rowPage);
    }

    /**
     * 수정 화면용 상세. soft-delete 상품은 @SQLRestriction으로 404.
     *
     * @throws ProductNotFoundException 미존재·삭제 시(404)
     */
    public AdminProductDetailResponse getProduct(String productPublicId) {
        Product product = productRepository.findByPublicId(productPublicId)
                .orElseThrow(() -> new ProductNotFoundException("상품을 찾을 수 없습니다: " + productPublicId));
        Seller seller = sellerRepository.findById(product.getSellerId()).orElse(null);
        Category category = categoryRepository.findById(product.getCategoryId()).orElse(null);

        List<ProductImage> images = productImageRepository.findByProductId(product.getId());
        List<ProductOptionGroup> allGroups = productOptionGroupRepository.findByProductId(product.getId());
        List<Long> groupIds = allGroups.stream().map(ProductOptionGroup::getId).toList();
        List<ProductOptionValue> allValues = groupIds.isEmpty()
                ? List.of()
                : productOptionValueRepository.findByOptionGroupIdIn(groupIds);
        List<ProductVariant> variants = productVariantRepository.findByProductId(product.getId());
        Map<Long, Inventory> inventoryByVariant = inventoryByVariantId(List.of(variants));

        Map<Long, ProductOptionGroup> groupById = allGroups.stream()
                .collect(Collectors.toMap(ProductOptionGroup::getId, Function.identity()));
        Map<Long, ProductOptionValue> valueById = allValues.stream()
                .collect(Collectors.toMap(ProductOptionValue::getId, Function.identity()));

        return new AdminProductDetailResponse(
                product.getPublicId(),
                product.getName(),
                product.getDescription(),
                product.getCategoryId(),
                category != null ? category.getDisplayName() : null,
                seller != null ? seller.getPublicId() : null,
                seller != null ? seller.getCompanyName() : null,
                product.getStatus(),
                product.getSaleStopSource(),
                product.isSoldoutManual(),
                product.getBasePrice(),
                product.getSupplyPrice(),
                product.getThumbnailUrl(),
                product.getSaleStartAt(),
                product.getSaleEndAt(),
                toImages(images),
                toOptionGroups(allGroups, allValues),
                toVariants(variants, inventoryByVariant, groupById, valueById));
    }

    // ==================== 매핑 ====================

    private AdminProductSummaryResponse toSummary(
            Product product,
            List<ProductVariant> variants,
            Map<Long, Inventory> inventoryByVariant,
            Seller seller,
            Category category) {
        int stockTotal = variants.stream()
                .map(variant -> inventoryByVariant.get(variant.getId()))
                .filter(inventory -> inventory != null)
                .mapToInt(Inventory::getQuantityAvailable)
                .sum();
        boolean soldOut = variants.stream().allMatch(variant ->
                ProductPurchasePolicy.isSoldOut(product, variant, inventoryByVariant.get(variant.getId()), 1));
        return new AdminProductSummaryResponse(
                product.getPublicId(),
                product.getName(),
                product.getThumbnailUrl(),
                seller != null ? seller.getPublicId() : null,
                seller != null ? seller.getCompanyName() : null,
                product.getCategoryId(),
                category != null ? category.getDisplayName() : null,
                stockTotal,
                product.getStatus(),
                product.getSaleStopSource(),
                soldOut,
                product.isSoldoutManual(),
                product.getBasePrice(),
                product.getSupplyPrice(),
                product.getSaleStartAt(),
                product.getSaleEndAt(),
                product.getCreatedAt());
    }

    private List<AdminProductDetailResponse.Image> toImages(List<ProductImage> images) {
        return images.stream()
                .sorted(Comparator.comparingInt(ProductImage::getDisplayOrder).thenComparing(ProductImage::getId))
                .map(image -> new AdminProductDetailResponse.Image(
                        image.getId(), image.getImageUrl(), image.getImageType(), image.getDisplayOrder(), image.isMain()))
                .toList();
    }

    private List<AdminProductDetailResponse.OptionGroup> toOptionGroups(
            List<ProductOptionGroup> allGroups, List<ProductOptionValue> allValues) {
        return allGroups.stream()
                .filter(group -> !ProductRegistrationService.DEFAULT_OPTION_GROUP_NAME.equals(group.getName()))
                .sorted(Comparator.comparingInt(ProductOptionGroup::getDisplayOrder))
                .map(group -> new AdminProductDetailResponse.OptionGroup(
                        group.getId(),
                        group.getName(),
                        group.getDisplayOrder(),
                        allValues.stream()
                                .filter(value -> value.getOptionGroup().getId().equals(group.getId()))
                                .sorted(Comparator.comparingInt(ProductOptionValue::getDisplayOrder))
                                .map(value -> new AdminProductDetailResponse.OptionValue(
                                        value.getId(), value.getValue(), value.getDisplayOrder()))
                                .toList()))
                .toList();
    }

    private List<AdminProductDetailResponse.Variant> toVariants(
            List<ProductVariant> variants,
            Map<Long, Inventory> inventoryByVariant,
            Map<Long, ProductOptionGroup> groupById,
            Map<Long, ProductOptionValue> valueById) {
        return variants.stream()
                .sorted(Comparator.comparingInt(ProductVariant::getDisplayOrder).thenComparing(ProductVariant::getId))
                .map(variant -> {
                    Inventory inventory = inventoryByVariant.get(variant.getId());
                    return new AdminProductDetailResponse.Variant(
                            variant.getPublicId(),
                            variant.getVariantCode(),
                            variant.getSellerSku(),
                            variant.getBarcode(),
                            variant.getAdditionalPrice(),
                            variant.getStatus(),
                            variant.isSoldoutManual(),
                            variant.getDisplayOrder(),
                            inventory != null ? inventory.getQuantityAvailable() : 0,
                            inventory != null ? inventory.getQuantityOnHand() : 0,
                            variantOptions(variant, groupById, valueById));
                })
                .toList();
    }

    /** variant의 option1~3 값을 (그룹·값) 조합으로 해소한다. DEFAULT sentinel 그룹 소속 값은 제외한다(단순상품은 빈 목록). */
    private List<AdminProductDetailResponse.VariantOption> variantOptions(
            ProductVariant variant, Map<Long, ProductOptionGroup> groupById, Map<Long, ProductOptionValue> valueById) {
        List<Long> valueIds = new ArrayList<>();
        valueIds.add(variant.getOption1ValueId());
        valueIds.add(variant.getOption2ValueId());
        valueIds.add(variant.getOption3ValueId());

        List<AdminProductDetailResponse.VariantOption> options = new ArrayList<>();
        for (Long valueId : valueIds) {
            if (valueId == null) {
                continue;
            }
            ProductOptionValue value = valueById.get(valueId);
            if (value == null) {
                continue;
            }
            ProductOptionGroup group = groupById.get(value.getOptionGroup().getId());
            if (group == null || ProductRegistrationService.DEFAULT_OPTION_GROUP_NAME.equals(group.getName())) {
                continue;
            }
            options.add(new AdminProductDetailResponse.VariantOption(
                    group.getId(), group.getName(), value.getId(), value.getValue()));
        }
        return options;
    }

    // ==================== 배치 조회 helper ====================

    private Map<Long, List<ProductVariant>> variantsByProductId(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productVariantRepository.findByProductIdIn(productIds).stream()
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

    private Map<Long, Seller> sellersByIdFor(List<Product> products) {
        List<Long> sellerIds = products.stream().map(Product::getSellerId).distinct().toList();
        if (sellerIds.isEmpty()) {
            return Map.of();
        }
        return sellerRepository.findByIdIn(sellerIds).stream()
                .collect(Collectors.toMap(Seller::getId, Function.identity()));
    }

    private Map<Long, Category> categoriesByIdFor(List<Product> products) {
        List<Long> categoryIds = products.stream().map(Product::getCategoryId).distinct().toList();
        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        return categoryRepository.findByIdIn(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
    }

    // ==================== 파라미터 정규화 ====================

    private Long resolveSellerId(String sellerPublicId) {
        if (sellerPublicId == null || sellerPublicId.isBlank()) {
            return null;
        }
        return sellerRepository.findByPublicId(sellerPublicId)
                .map(Seller::getId)
                .orElseThrow(() -> new SellerNotFoundException("셀러를 찾을 수 없습니다: " + sellerPublicId));
    }

    private String normalizeKeyword(String keyword) {
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

    /** LIKE 와일드카드(%·_)와 escape 문자(\)를 {@code \}로 escape해 리터럴 매칭시킨다(ProductCatalogService.toLikePattern 정합). */
    private String toLikePattern(String trimmedKeyword) {
        if (trimmedKeyword == null) {
            return null;
        }
        String escaped = trimmedKeyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private Sort toSort(AdminProductSort sort) {
        return switch (sort) {
            case NAME -> Sort.by(Sort.Order.asc("name"), Sort.Order.desc("id"));
            case PRICE_ASC -> Sort.by(Sort.Order.asc("basePrice"), Sort.Order.desc("id"));
            case PRICE_DESC -> Sort.by(Sort.Order.desc("basePrice"), Sort.Order.desc("id"));
            case LATEST -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        };
    }

    private int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
