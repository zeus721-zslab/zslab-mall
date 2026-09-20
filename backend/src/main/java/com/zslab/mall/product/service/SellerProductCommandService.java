package com.zslab.mall.product.service;

import com.zslab.mall.category.exception.CategoryNotFoundException;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.file.service.ImageUploadService;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.product.controller.request.SellerProductImagesRequest;
import com.zslab.mall.product.controller.request.SellerProductUpdateRequest;
import com.zslab.mall.product.controller.request.SellerProductVariantsRequest;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductImage;
import com.zslab.mall.product.entity.ProductOptionGroup;
import com.zslab.mall.product.entity.ProductOptionValue;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.enums.ProductImageType;
import com.zslab.mall.product.enums.ProductVariantStatus;
import com.zslab.mall.product.exception.ProductImageNotFoundException;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.exception.ProductVariantNotFoundException;
import com.zslab.mall.product.exception.ProductVariantOptionConflictException;
import com.zslab.mall.product.repository.ProductImageRepository;
import com.zslab.mall.product.repository.ProductOptionGroupRepository;
import com.zslab.mall.product.repository.ProductOptionValueRepository;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import com.zslab.mall.product.repository.VariantOptionCombinationProjection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 상품 변경 Application Service(Track 90-C-2): 기본정보 수정·이미지 메타 치환·variant 메타 수정/신규 추가. 저장 즉시 반영이며
 * 관리자 승인을 거치지 않는다. 상품 상태(승인·판매중지)·공급가·판매기간·셀러는 관리자 소관이라 바꾸지 않는다. 트랜잭션 경계는 메서드 단위다.
 *
 * <p>소유권은 {@code findByPublicIdForUpdate}(비관락) 후 {@code product.seller_id = 액터} 대조로 강제하며 타 셀러·미존재·삭제는 모두 404로
 * 은닉한다(셀러 조회 관례). 관리자 {@code AdminProductCommandService}·{@code AdminProductVariantService}는 재사용하지 않고 로직을 복제한다
 * (관리자 서비스 무수정·감사 컨텍스트 미주입). <b>셀러 조작 감사 로그는 남기지 않는다(셀러 감사 이월).</b>
 *
 * <p><b>관리자 variant 치환과의 차이</b>: 관리자 PUT은 목록에 없는 기존 variant를 soft-delete하지만, 셀러 PUT은 <b>목록에 없는 기존 variant를
 * 건드리지 않는다</b>(삭제 없음·비활성화는 status=HIDDEN). 옵션 그룹·값 구조도 바꾸지 않아 신규 variant는 기존 옵션값 조합으로만 만든다
 * (없는 옵션값은 400). 조합 중복은 <b>soft-delete된 variant까지 포함한</b> in-memory 선검증(409·{@code findOptionCombinationsIncludingDeleted})
 * + flush 시점 {@code uk_product_variant_options} 위반 409 변환의 이중 방어다 — 1·2·3슬롯 어느 상품이든 삭제된 조합의 재생성은 같은 409로 거부된다
 * (UK는 option2/3 NULL 조합에서 발동하지 않으므로 앱 선검증이 주 방어선·관리자 서비스의 "3슬롯에서만" 한계와 다름). UK 외 무결성 위반은 변환하지 않고
 * 그대로 전파한다.
 *
 * <p>이미지 URL은 <b>본 셀러에게 발급된</b> 업로드 경로만 신규 등록·URL 변경을 허용한다({@code requireSellerOwnedProductUrl}·검토 반영). 기존 행의
 * URL을 그대로 되돌리는 편집은 검증을 건너뛰어 관리자가 붙인 이미지의 보존 편집이 깨지지 않는다. 대표(GALLERY main) 미지정 시 첫 GALLERY를
 * 썸네일로 동기화하고 GALLERY가 없으면 썸네일을 비운다.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SellerProductCommandService {

    /** 옵션 조합 UK 이름(V1). DataIntegrityViolation 중 이 제약 위반만 409로 변환한다. */
    static final String OPTION_COMBINATION_CONSTRAINT = "uk_product_variant_options";

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductOptionGroupRepository productOptionGroupRepository;
    private final ProductOptionValueRepository productOptionValueRepository;
    private final CategoryRepository categoryRepository;
    private final InventoryService inventoryService;
    private final ImageUploadService imageUploadService;

    /**
     * 기본정보 수정(categoryId·name·description·basePrice). {@link Product#updateBasicInfo}는 전체 치환 시그니처라 셀러가 바꿀 수 없는
     * 공급가·썸네일·판매기간은 현재 값을 그대로 넘겨 보존한다(누락 시 null로 소실되는 함정). status·sellerId는 엔티티가 불변 보장.
     *
     * @throws ProductNotFoundException 미존재·삭제·타 셀러 상품(404)
     * @throws CategoryNotFoundException categoryId 미존재(404)
     */
    public void updateBasicInfo(Long sellerId, String productPublicId, SellerProductUpdateRequest request) {
        Product product = findOwnedForUpdate(sellerId, productPublicId);
        if (!categoryRepository.existsById(request.categoryId())) {
            throw new CategoryNotFoundException("상품 카테고리가 존재하지 않습니다: categoryId=" + request.categoryId());
        }
        product.updateBasicInfo(
                request.categoryId(),
                request.name(),
                request.description(),
                request.basePrice(),
                product.getSupplyPrice(),
                product.getThumbnailUrl(),
                product.getSaleStartAt(),
                product.getSaleEndAt());
        log.info("[SellerProduct] 기본정보 수정 sellerId={} productPublicId={}", sellerId, productPublicId);
    }

    /**
     * 이미지 메타 전체 치환(관리자 replaceImages 복제). 목록 순서=display_order, 미포함 기존 이미지 soft-delete, 대표는 GALLERY 1장 이하.
     * 썸네일은 대표가 있으면 대표, 없으면 요청의 첫 GALLERY로 동기화하고 GALLERY가 없으면 null로 비운다(검토 반영).
     *
     * @throws ProductNotFoundException 미존재·삭제·타 셀러 상품(404)
     * @throws ProductImageNotFoundException imageId가 이 상품의 활성 이미지가 아닐 때(404)
     * @throws IllegalArgumentException 대표 2장 이상·DETAIL 대표 지정(400)
     * @throws MalformedRequestException 신규·변경 imageUrl이 본 셀러에게 발급된 상품 이미지 경로가 아니거나 저장 파일이 없는 경우(400·D-174·검토 반영)
     */
    public void replaceImages(Long sellerId, String productPublicId, SellerProductImagesRequest request) {
        Product product = findOwnedForUpdate(sellerId, productPublicId);
        validateImages(request);

        Map<Long, ProductImage> existingById = productImageRepository.findByProductId(product.getId()).stream()
                .collect(Collectors.toMap(ProductImage::getId, Function.identity()));
        for (int order = 0; order < request.images().size(); order++) {
            SellerProductImagesRequest.Item item = request.images().get(order);
            ProductImageType imageType = ProductImageType.valueOf(item.imageType());
            if (item.imageId() == null) {
                imageUploadService.requireSellerOwnedProductUrl(item.imageUrl(), sellerId);
                productImageRepository.save(ProductImage.create(product, item.imageUrl(), imageType, order, item.main()));
                continue;
            }
            ProductImage existing = existingById.remove(item.imageId());
            if (existing == null) {
                throw new ProductImageNotFoundException(
                        "상품 이미지를 찾을 수 없습니다: productPublicId=" + productPublicId + ", imageId=" + item.imageId());
            }
            // D-174: 기존 행의 URL을 그대로 되돌려 보내는 편집은 통과(관리자가 붙인 이미지 보존), URL을 바꾸는 경우만 본 셀러 발급 경로 강제.
            if (!item.imageUrl().equals(existing.getImageUrl())) {
                imageUploadService.requireSellerOwnedProductUrl(item.imageUrl(), sellerId);
            }
            existing.updateMeta(item.imageUrl(), imageType, order, item.main());
        }
        // 목록에 남지 않은 기존 이미지는 soft-delete(FK RESTRICT·하드 삭제 금지).
        existingById.values().forEach(ProductImage::markDeleted);
        // 썸네일 동기화(검토 반영): 대표가 있으면 대표, 없으면 요청의 첫 GALLERY, GALLERY가 없으면 null(썸네일 없음).
        String thumbnailSource = request.images().stream()
                .filter(SellerProductImagesRequest.Item::main)
                .findFirst()
                .or(() -> request.images().stream()
                        .filter(item -> ProductImageType.GALLERY.name().equals(item.imageType()))
                        .findFirst())
                .map(SellerProductImagesRequest.Item::imageUrl)
                .orElse(null);
        product.changeThumbnailUrl(thumbnailSource == null ? null : imageUploadService.thumbnailUrlFor(thumbnailSource));
        log.info("[SellerProduct] 이미지 메타 치환 sellerId={} productPublicId={} count={} deleted={}",
                sellerId, productPublicId, request.images().size(), existingById.size());
    }

    /**
     * variant 메타 수정 + 신규 추가. 기존분은 {@link ProductVariant#updateMeta}만(옵션 조합·재고 불변), 신규분은 기존 옵션값 조합을 해소해
     * 생성하고 초기 재고를 시딩한다. 목록에 없는 기존 variant는 그대로 둔다(클래스 Javadoc).
     *
     * @throws ProductNotFoundException 미존재·삭제·타 셀러 상품(404)
     * @throws ProductVariantNotFoundException variantPublicId가 이 상품의 활성 variant가 아닐 때(404)
     * @throws MalformedRequestException 요청 내 variantPublicId 중복(400)
     * @throws IllegalArgumentException 옵션 그룹 불일치·옵션값 미존재·단순상품 옵션 지정·그룹 누락/중복(400)
     * @throws ProductVariantOptionConflictException 동일 옵션 조합 중복(soft-delete된 조합 포함·409)
     */
    public void replaceVariants(Long sellerId, String productPublicId, SellerProductVariantsRequest request) {
        Product product = findOwnedForUpdate(sellerId, productPublicId);
        rejectDuplicatePublicIds(request);

        List<ProductOptionGroup> groups = productOptionGroupRepository.findByProductId(product.getId()).stream()
                .sorted(Comparator.comparingInt(ProductOptionGroup::getDisplayOrder).thenComparing(ProductOptionGroup::getId))
                .toList();
        boolean simpleProduct = groups.size() == 1
                && ProductRegistrationService.DEFAULT_OPTION_GROUP_NAME.equals(groups.get(0).getName());
        Map<Long, List<ProductOptionValue>> valuesByGroupId = loadValuesByGroupId(groups);

        Map<String, ProductVariant> existingByPublicId = productVariantRepository.findByProductId(product.getId()).stream()
                .collect(Collectors.toMap(ProductVariant::getPublicId, Function.identity()));
        // 조합 중복 기준 집합 = soft-delete 포함 전체 variant(uk는 option2/3 NULL 조합에서 발동하지 않으므로 앱 선검증이 주 방어선).
        Set<List<Long>> combinations = new HashSet<>();
        for (VariantOptionCombinationProjection existing : productVariantRepository.findOptionCombinationsIncludingDeleted(product.getId())) {
            combinations.add(Arrays.asList(existing.getOption1ValueId(), existing.getOption2ValueId(), existing.getOption3ValueId()));
        }

        int updatedCount = 0;
        int createdCount = 0;
        try {
            for (SellerProductVariantsRequest.Item item : request.variants()) {
                if (item.variantPublicId() != null) {
                    ProductVariant existing = existingByPublicId.get(item.variantPublicId());
                    if (existing == null) {
                        throw new ProductVariantNotFoundException("상품 변형을 찾을 수 없습니다: productPublicId=" + productPublicId
                                + ", variantPublicId=" + item.variantPublicId());
                    }
                    existing.updateMeta(item.variantCode(), item.sellerSku(), item.barcode(), item.additionalPrice(),
                            ProductVariantStatus.valueOf(item.status()), item.soldoutManual(), item.displayOrder());
                    updatedCount++;
                    continue;
                }
                Long[] slots = resolveSlots(item, groups, simpleProduct, valuesByGroupId);
                if (!combinations.add(Arrays.asList(slots))) {
                    throw new ProductVariantOptionConflictException(
                            "동일 옵션 조합의 상품 변형이 이미 존재합니다: variantCode=" + item.variantCode());
                }
                ProductVariant created = productVariantRepository.save(ProductVariant.create(
                        product.getId(), item.variantCode(), item.sellerSku(), item.barcode(), item.additionalPrice(),
                        item.displayOrder(), slots[0], slots[1], slots[2]));
                created.updateMeta(item.variantCode(), item.sellerSku(), item.barcode(), item.additionalPrice(),
                        ProductVariantStatus.valueOf(item.status()), item.soldoutManual(), item.displayOrder());
                inventoryService.initializeInventory(created.getId(), product.getId(), item.initialStock());
                createdCount++;
            }
            // 명시적 flush로 uk_product_variant_options(soft-delete된 3슬롯 조합 포함) 위반을 트랜잭션 내에서 표면화한다.
            productVariantRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            // 옵션 조합 UK 위반만 409로 변환하고 그 외 무결성 위반(다른 제약·NOT NULL 등)은 은닉하지 않고 그대로 전파한다(검토 반영).
            String cause = exception.getMostSpecificCause().getMessage();
            if (cause == null || !cause.contains(OPTION_COMBINATION_CONSTRAINT)) {
                throw exception;
            }
            log.warn("[SellerProduct] 옵션 조합 중복 차단(409·{}) productPublicId={}: {}", OPTION_COMBINATION_CONSTRAINT, productPublicId, cause);
            throw new ProductVariantOptionConflictException("동일 옵션 조합의 상품 변형이 이미 존재합니다(" + OPTION_COMBINATION_CONSTRAINT + ").");
        }
        log.info("[SellerProduct] variant 수정 sellerId={} productPublicId={} updated={} created={}",
                sellerId, productPublicId, updatedCount, createdCount);
    }

    // ==================== helpers ====================

    /**
     * @throws MalformedRequestException 요청 내 같은 variantPublicId가 2회 이상 나올 때(400·같은 행을 두 번 수정하려는 잘못된 요청)
     */
    private static void rejectDuplicatePublicIds(SellerProductVariantsRequest request) {
        Set<String> seen = new HashSet<>();
        for (SellerProductVariantsRequest.Item item : request.variants()) {
            if (item.variantPublicId() != null && !seen.add(item.variantPublicId())) {
                throw new MalformedRequestException("요청에 같은 variantPublicId가 중복됩니다: " + item.variantPublicId());
            }
        }
    }

    /**
     * @throws ProductNotFoundException 미존재·삭제·타 셀러 상품(404·존재 은닉)
     */
    private Product findOwnedForUpdate(Long sellerId, String productPublicId) {
        return productRepository.findByPublicIdForUpdate(productPublicId)
                .filter(candidate -> candidate.getSellerId().equals(sellerId))
                .orElseThrow(() -> new ProductNotFoundException("상품을 찾을 수 없습니다: publicId=" + productPublicId));
    }

    private static void validateImages(SellerProductImagesRequest request) {
        long mainCount = 0;
        for (SellerProductImagesRequest.Item item : request.images()) {
            if (!item.main()) {
                continue;
            }
            if (!ProductImageType.GALLERY.name().equals(item.imageType())) {
                throw new IllegalArgumentException("대표 이미지는 GALLERY 유형만 지정할 수 있습니다.");
            }
            mainCount++;
        }
        if (mainCount > 1) {
            throw new IllegalArgumentException("대표 이미지는 1장만 지정할 수 있습니다.");
        }
    }

    private Map<Long, List<ProductOptionValue>> loadValuesByGroupId(List<ProductOptionGroup> groups) {
        List<Long> groupIds = groups.stream().map(ProductOptionGroup::getId).toList();
        Map<Long, List<ProductOptionValue>> valuesByGroupId = new HashMap<>();
        if (groupIds.isEmpty()) {
            return valuesByGroupId;
        }
        for (ProductOptionValue value : productOptionValueRepository.findByOptionGroupIdIn(groupIds)) {
            valuesByGroupId.computeIfAbsent(value.getOptionGroup().getId(), key -> new ArrayList<>()).add(value);
        }
        return valuesByGroupId;
    }

    /**
     * 신규 variant의 options를 option1~3 valueId 슬롯으로 해소한다. 그룹 displayOrder 순서가 슬롯 순서이며, 상품의 모든(비-DEFAULT) 그룹을
     * 정확히 한 번씩 지정해야 한다. 값은 그룹의 기존 옵션값에서만 찾는다(없으면 400·옵션 구조 불변).
     */
    private static Long[] resolveSlots(
            SellerProductVariantsRequest.Item item,
            List<ProductOptionGroup> groups,
            boolean simpleProduct,
            Map<Long, List<ProductOptionValue>> valuesByGroupId) {
        List<SellerProductVariantsRequest.Option> options = item.options() == null ? List.of() : item.options();
        Long[] slots = new Long[3];
        if (simpleProduct) {
            if (!options.isEmpty()) {
                throw new IllegalArgumentException("단순상품(옵션 없음)의 variant에는 options를 지정할 수 없습니다.");
            }
            ProductOptionGroup defaultGroup = groups.get(0);
            slots[0] = valuesByGroupId.getOrDefault(defaultGroup.getId(), List.of()).stream()
                    .findFirst()
                    .map(ProductOptionValue::getId)
                    .orElseThrow(() -> new IllegalStateException("DEFAULT 옵션값이 없습니다: groupId=" + defaultGroup.getId()));
            return slots;
        }
        if (options.size() != groups.size()) {
            throw new IllegalArgumentException("신규 variant는 상품의 옵션 그룹 " + groups.size() + "개를 모두 지정해야 합니다.");
        }
        Map<Long, String> valueByGroupId = new HashMap<>();
        for (SellerProductVariantsRequest.Option option : options) {
            if (valueByGroupId.put(option.optionGroupId(), option.value()) != null) {
                throw new IllegalArgumentException("옵션 그룹이 중복 지정되었습니다: optionGroupId=" + option.optionGroupId());
            }
        }
        for (int slot = 0; slot < groups.size(); slot++) {
            ProductOptionGroup group = groups.get(slot);
            String value = valueByGroupId.get(group.getId());
            if (value == null) {
                throw new IllegalArgumentException("이 상품의 옵션 그룹이 아니거나 누락되었습니다: optionGroupId=" + group.getId());
            }
            slots[slot] = valuesByGroupId.getOrDefault(group.getId(), List.of()).stream()
                    .filter(candidate -> candidate.getValue().equals(value))
                    .findFirst()
                    .map(ProductOptionValue::getId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "옵션 그룹에 없는 옵션값입니다(옵션 구조는 변경할 수 없습니다): optionGroupId=" + group.getId() + ", value=" + value));
        }
        return slots;
    }
}
