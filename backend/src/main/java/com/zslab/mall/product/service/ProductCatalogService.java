package com.zslab.mall.product.service;

import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.common.exception.MalformedRequestException;
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
import com.zslab.mall.product.policy.ProductPurchasePolicy;
import com.zslab.mall.product.repository.ProductImageRepository;
import com.zslab.mall.product.repository.ProductOptionGroupRepository;
import com.zslab.mall.product.repository.ProductOptionValueRepository;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.repository.SellerRepository;
import java.time.LocalDateTime;
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
 * <p><b>노출·품절·대표가 정책 단일화</b>: 노출대상(D1)은 Repository 쿼리(Product.status=SALE ∧ Seller.status=ACTIVE ∧ 판매기간 내)가,
 * 대표가(D3)는 본 서비스의 {@link #displayPrice}가, 품절(D2)은 {@link ProductPurchasePolicy}(Track 76·전 구매 경로 공용)가 계산한다.
 * 상품 단위 soldOut은 판매가능 variant가 하나도 구매가능하지 않을 때 true다(상품 수동품절이면 전 variant가 구매불가라 자동 true).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProductCatalogService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    // 상품명 검색어 최대 길이(Track 72). product.name VARCHAR(200)보다 짧게 잡아 과도한 패턴을 차단한다.
    private static final int MAX_KEYWORD_LENGTH = 50;

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

    /**
     * 노출대상 상품 목록(D1 노출·D2 품절·D3 대표가·페이징·정렬·상품명 keyword). size는 1~100 클램프(BuyerOrderQueryService 정합).
     *
     * @throws MalformedRequestException keyword가 trim 후 {@value #MAX_KEYWORD_LENGTH}자를 초과할 때(400)
     */
    public PagedResponse<ProductSummaryResponse> listProducts(
            Long categoryId, String keyword, ProductCatalogSort sort, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        LocalDateTime now = LocalDateTime.now();
        Page<Product> products = productRepository.findDisplayable(
                now, categoryId, toLikePattern(keyword), sort.name(), pageable);
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

    /**
     * 노출대상 단건 상세. 미존재·비노출(status∉{SALE,STOPPED}·판매자 비-ACTIVE·삭제)은 전부 404로 은닉한다(§2).
     * 판매기간 밖(Track 76)은 STOPPED와 동일하게 상세 접속을 허용하되 {@code saleStopped=true}로 표기해 담기를 차단한다
     * (사용자 FE 무수정·"판매중지" 라벨 재사용).
     */
    public ProductDetailResponse getProduct(String productPublicId) {
        Product product = productRepository.findByPublicId(productPublicId)
                .orElseThrow(() -> new ProductNotFoundException("상품을 찾을 수 없습니다: " + productPublicId));
        // Track 71: 판매중지(STOPPED) 상품은 상세 접속을 허용해 "판매중지"로 표기한다. 그 외 비-SALE은 404 은닉 유지.
        if (product.getStatus() != ProductStatus.SALE && product.getStatus() != ProductStatus.STOPPED) {
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
                allGroups, allValues, groupNameById, valueById, LocalDateTime.now());
    }

    // ==================== 정책 계산(단일화) ====================

    /** 대표가(D3) = basePrice + 판매가능 variant의 MIN(additional_price). 판매가능 variant가 없으면 basePrice. */
    private long displayPrice(Product product, List<ProductVariant> saleVariants) {
        return saleVariants.stream()
                .mapToLong(ProductVariant::getAdditionalPrice)
                .min()
                .orElse(0L) + product.getBasePrice();
    }

    /**
     * variant 품절 여부의 부정(D2). 품절 축(상품·변형 수동품절·변형 비-SALE·재고 0)만 본다 — 상품 판매 상태(STOPPED·판매기간 밖)는
     * 상세 화면이 saleStopped로 별도 표기하므로 variant soldOut에 섞지 않는다(Track 71 동작 보존). 실제 구매 가능 여부는
     * {@link ProductPurchasePolicy}가 담기·주문 시점에 판정한다.
     */
    private boolean isPurchasable(Product product, ProductVariant variant, Map<Long, Inventory> inventoryByVariant) {
        return !ProductPurchasePolicy.isSoldOut(product, variant, inventoryByVariant.get(variant.getId()), 1);
    }

    /** 상품 단위 품절 = 판매가능 variant가 하나도 구매가능하지 않음(없어도 품절·D2). */
    private boolean isProductSoldOut(
            Product product, List<ProductVariant> saleVariants, Map<Long, Inventory> inventoryByVariant) {
        return saleVariants.stream().noneMatch(variant -> isPurchasable(product, variant, inventoryByVariant));
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
                isProductSoldOut(product, saleVariants, inventoryByVariant),
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
            Map<Long, ProductOptionValue> valueById,
            LocalDateTime now) {

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
                        !isPurchasable(product, variant, inventoryByVariant),
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
                isProductSoldOut(product, saleVariants, inventoryByVariant),
                !ProductPurchasePolicy.isOnSale(product, now),
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

    /**
     * keyword를 상품명 LIKE 패턴으로 정규화한다(Track 72). trim 후 빈 값이면 null(조건 없음), 길이 초과면 400.
     * LIKE 와일드카드(%·_)와 escape 문자(\)를 {@code \}로 escape해 리터럴 매칭시킨다(Repository 쿼리의 ESCAPE '\' 계약).
     */
    private String toLikePattern(String keyword) {
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
        String escaped = trimmed
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
