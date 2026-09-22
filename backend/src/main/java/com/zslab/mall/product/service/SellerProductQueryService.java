package com.zslab.mall.product.service;

import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.repository.InventoryRepository;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.product.controller.request.SellerProductSort;
import com.zslab.mall.product.controller.response.SellerProductDetailResponse;
import com.zslab.mall.product.controller.response.SellerProductSummaryResponse;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductImage;
import com.zslab.mall.product.entity.ProductOptionGroup;
import com.zslab.mall.product.entity.ProductOptionValue;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.repository.ProductImageRepository;
import com.zslab.mall.product.repository.ProductOptionGroupRepository;
import com.zslab.mall.product.repository.ProductOptionValueRepository;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import com.zslab.mall.product.repository.SellerProductSpecifications;
import java.util.ArrayList;
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
 * 셀러 상품 조회 서비스(Track 90-C-1·read-only). 자기 상품 목록(필터·정렬·페이징)과 수정 화면용 상세를 담당한다. 노출 필터가
 * 없다는 점(모든 상태 포함·soft-delete만 제외)은 관리자와 같고, 소유 조건({@code product.seller_id = 액터})이 항상 붙는 점과
 * 셀러 식별·공급가·판매기간을 응답에서 뺀 점이 다르다. 관리자 {@code AdminProductQueryService}는 재사용하지 않고 조립을 복제한다
 * (관리자 서비스 무수정·셀러 계약 독립·90-B 관례).
 *
 * <p>목록은 count 1 + page 1 + variant·category 배치 2 = 4쿼리, 상세는 상품 1 + 카테고리 1 + 이미지·그룹·값·variant·재고 5 = 7쿼리로
 * 고정된다(N+1 없음). 타 셀러 상품·미존재는 모두 404(존재 은닉·셀러 조회 관례).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerProductQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 50;

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductOptionGroupRepository productOptionGroupRepository;
    private final ProductOptionValueRepository productOptionValueRepository;
    private final ProductImageRepository productImageRepository;
    private final InventoryRepository inventoryRepository;
    private final CategoryRepository categoryRepository;

    /**
     * 셀러 상품 목록. keyword는 상품명 부분일치.
     *
     * @throws MalformedRequestException keyword가 trim 후 {@value #MAX_KEYWORD_LENGTH}자를 초과할 때(400)
     */
    public PagedResponse<SellerProductSummaryResponse> listProducts(Long sellerId, String keyword, ProductStatus status,
            Long categoryId, SellerProductSort sort, int page, int size) {
        Specification<Product> specification = Specification
                .where(SellerProductSpecifications.sellerId(sellerId))
                .and(SellerProductSpecifications.keyword(toLikePattern(normalizeKeyword(keyword))))
                .and(SellerProductSpecifications.status(status))
                .and(SellerProductSpecifications.categoryId(categoryId));
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), toSort(sort));
        Page<Product> products = productRepository.findAll(specification, pageable);
        List<Product> content = products.getContent();

        Map<Long, Long> variantCountByProductId = variantCountByProductId(content);
        Map<Long, Category> categoryById = categoriesByIdFor(content);
        List<SellerProductSummaryResponse> rows = content.stream()
                .map(product -> new SellerProductSummaryResponse(
                        product.getPublicId(),
                        product.getName(),
                        product.getCategoryId(),
                        categoryName(categoryById.get(product.getCategoryId())),
                        product.getStatus(),
                        product.getSaleStopSource(),
                        product.getBasePrice(),
                        product.getThumbnailUrl(),
                        variantCountByProductId.getOrDefault(product.getId(), 0L).intValue(),
                        product.getCreatedAt(),
                        product.getUpdatedAt()))
                .toList();
        return PagedResponse.from(new PageImpl<>(rows, pageable, products.getTotalElements()));
    }

    /**
     * 셀러 상품 상세(이미지·옵션·variant·재고 3수치 포함).
     *
     * @throws ProductNotFoundException 미존재·삭제·타 셀러 상품(404·존재 은닉)
     */
    public SellerProductDetailResponse getProduct(Long sellerId, String productPublicId) {
        Product product = productRepository.findByPublicId(productPublicId)
                .filter(candidate -> candidate.getSellerId().equals(sellerId))
                .orElseThrow(() -> new ProductNotFoundException("상품을 찾을 수 없습니다: publicId=" + productPublicId));
        Category category = categoryRepository.findById(product.getCategoryId()).orElse(null);

        List<ProductImage> images = productImageRepository.findByProductId(product.getId());
        List<ProductOptionGroup> allGroups = productOptionGroupRepository.findByProductId(product.getId());
        List<Long> groupIds = allGroups.stream().map(ProductOptionGroup::getId).toList();
        List<ProductOptionValue> allValues = groupIds.isEmpty()
                ? List.of()
                : productOptionValueRepository.findByOptionGroupIdIn(groupIds);
        List<ProductVariant> variants = productVariantRepository.findByProductId(product.getId());
        Map<Long, Inventory> inventoryByVariantId = inventoryByVariantId(variants);

        Map<Long, ProductOptionGroup> groupById = allGroups.stream()
                .collect(Collectors.toMap(ProductOptionGroup::getId, Function.identity()));
        Map<Long, ProductOptionValue> valueById = allValues.stream()
                .collect(Collectors.toMap(ProductOptionValue::getId, Function.identity()));

        return new SellerProductDetailResponse(
                product.getPublicId(),
                product.getName(),
                product.getDescription(),
                product.getCategoryId(),
                categoryName(category),
                product.getStatus(),
                product.getSaleStopSource(),
                product.getBasePrice(),
                product.getThumbnailUrl(),
                product.isSoldoutManual(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                toImages(images),
                toOptionGroups(allGroups, allValues),
                toVariants(variants, inventoryByVariantId, groupById, valueById));
    }

    // ==================== 매핑 ====================

    private static List<SellerProductDetailResponse.Image> toImages(List<ProductImage> images) {
        return images.stream()
                .sorted(Comparator.comparingInt(ProductImage::getDisplayOrder).thenComparing(ProductImage::getId))
                .map(image -> new SellerProductDetailResponse.Image(
                        image.getId(), image.getImageUrl(), image.getImageType(), image.getDisplayOrder(), image.isMain()))
                .toList();
    }

    /** DEFAULT sentinel 그룹(단순상품)은 옵션 그룹 목록에서 제외한다(관리자 상세와 동일). */
    private static List<SellerProductDetailResponse.OptionGroup> toOptionGroups(
            List<ProductOptionGroup> allGroups, List<ProductOptionValue> allValues) {
        return allGroups.stream()
                .filter(group -> !ProductRegistrationService.DEFAULT_OPTION_GROUP_NAME.equals(group.getName()))
                .sorted(Comparator.comparingInt(ProductOptionGroup::getDisplayOrder))
                .map(group -> new SellerProductDetailResponse.OptionGroup(
                        group.getId(),
                        group.getName(),
                        group.getDisplayOrder(),
                        allValues.stream()
                                .filter(value -> value.getOptionGroup().getId().equals(group.getId()))
                                .sorted(Comparator.comparingInt(ProductOptionValue::getDisplayOrder))
                                .map(value -> new SellerProductDetailResponse.OptionValue(
                                        value.getId(), value.getValue(), value.getDisplayOrder()))
                                .toList()))
                .toList();
    }

    private static List<SellerProductDetailResponse.Variant> toVariants(
            List<ProductVariant> variants,
            Map<Long, Inventory> inventoryByVariantId,
            Map<Long, ProductOptionGroup> groupById,
            Map<Long, ProductOptionValue> valueById) {
        return variants.stream()
                .sorted(Comparator.comparingInt(ProductVariant::getDisplayOrder).thenComparing(ProductVariant::getId))
                .map(variant -> {
                    Inventory inventory = inventoryByVariantId.get(variant.getId());
                    return new SellerProductDetailResponse.Variant(
                            variant.getPublicId(),
                            variant.getVariantCode(),
                            variant.getSellerSku(),
                            variant.getBarcode(),
                            variant.getAdditionalPrice(),
                            variant.getStatus(),
                            variant.isSoldoutManual(),
                            variant.getDisplayOrder(),
                            variantOptions(variant, groupById, valueById),
                            inventory != null ? inventory.getQuantityOnHand() : 0,
                            inventory != null ? inventory.getQuantityReserved() : 0,
                            inventory != null ? inventory.getQuantityAvailable() : 0);
                })
                .toList();
    }

    /** variant의 option1~3 값을 (그룹·값) 조합으로 해소한다. DEFAULT sentinel 그룹 소속 값은 제외한다(단순상품은 빈 목록). */
    private static List<SellerProductDetailResponse.VariantOption> variantOptions(
            ProductVariant variant, Map<Long, ProductOptionGroup> groupById, Map<Long, ProductOptionValue> valueById) {
        List<Long> valueIds = new ArrayList<>();
        valueIds.add(variant.getOption1ValueId());
        valueIds.add(variant.getOption2ValueId());
        valueIds.add(variant.getOption3ValueId());

        List<SellerProductDetailResponse.VariantOption> options = new ArrayList<>();
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
            options.add(new SellerProductDetailResponse.VariantOption(group.getId(), value.getId(), value.getValue()));
        }
        return options;
    }

    private static String categoryName(Category category) {
        return category != null ? category.getDisplayName() : null;
    }

    // ==================== 배치 조회 helper ====================

    private Map<Long, Long> variantCountByProductId(List<Product> products) {
        List<Long> productIds = products.stream().map(Product::getId).toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productVariantRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.groupingBy(ProductVariant::getProductId, Collectors.counting()));
    }

    private Map<Long, Inventory> inventoryByVariantId(List<ProductVariant> variants) {
        List<Long> variantIds = variants.stream().map(ProductVariant::getId).toList();
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        return inventoryRepository.findByVariantIdIn(variantIds).stream()
                .collect(Collectors.toMap(Inventory::getVariantId, Function.identity()));
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

    /** LIKE 와일드카드(%·_)와 escape 문자(\)를 {@code \}로 escape해 리터럴 매칭시킨다(셀러 품목 목록 정합). */
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

    private static Sort toSort(SellerProductSort sort) {
        return switch (sort) {
            case NAME -> Sort.by(Sort.Order.asc("name"), Sort.Order.desc("id"));
            case PRICE_ASC -> Sort.by(Sort.Order.asc("basePrice"), Sort.Order.desc("id"));
            case PRICE_DESC -> Sort.by(Sort.Order.desc("basePrice"), Sort.Order.desc("id"));
            case LATEST -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        };
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
