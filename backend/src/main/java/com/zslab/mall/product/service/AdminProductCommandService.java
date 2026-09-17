package com.zslab.mall.product.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.category.exception.CategoryNotFoundException;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.file.service.ImageUploadService;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.product.controller.request.AdminProductCreateRequest;
import com.zslab.mall.product.controller.request.AdminProductImagesRequest;
import com.zslab.mall.product.controller.request.AdminProductUpdateRequest;
import com.zslab.mall.product.controller.response.ProductRegistrationResponse;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductImage;
import com.zslab.mall.product.enums.ProductImageType;
import com.zslab.mall.product.exception.ProductHasOrderHistoryException;
import com.zslab.mall.product.exception.ProductImageNotFoundException;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.repository.ProductImageRepository;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.exception.SellerNotFoundException;
import com.zslab.mall.seller.repository.SellerRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 상품 변경 Application Service(Track 76): 등록·기본정보 수정·이미지 메타 치환·수동 품절·soft-delete. variant 치환은
 * {@link AdminProductVariantService}, 일괄 변경은 {@link AdminProductBulkService}, 상태 전이는 기존 승인/판매상태 서비스가 담당한다.
 *
 * <p>등록은 셀러 등록 {@link ProductRegistrationService#registerProduct}를 그대로 재사용한다(sellerId를 관리자가 지정·PENDING 생성)
 * 하고, 같은 트랜잭션에서 관리자 전용 필드(공급가·판매기간)를 {@link Product#applySaleTerms}로 덧씌운다. 운영자 조작이므로 전부
 * 감사 로그(UPDATE/DELETE·before/after 필드맵)를 적재한다(ProductSaleStatusService 패턴).
 *
 * <p>판매기간 입력은 ISO offset이며 KST LocalDateTime으로 변환해 저장한다(DB DATETIME(6)·hibernate time_zone Asia/Seoul 정합).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AdminProductCommandService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final SellerRepository sellerRepository;
    private final CategoryRepository categoryRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRegistrationService productRegistrationService;
    private final ImageUploadService imageUploadService;
    private final AuditRecorder auditRecorder;

    /**
     * 관리자 상품 등록. 셀러 지정 + 셀러 등록 Service 재사용(PENDING) + 공급가·판매기간 적용.
     *
     * @throws SellerNotFoundException sellerPublicId 미존재(404)
     * @throws IllegalArgumentException 판매 시작 ≥ 종료(400)
     */
    public ProductRegistrationResponse create(AdminProductCreateRequest request, AuditContext auditContext) {
        Seller seller = sellerRepository.findByPublicId(request.sellerPublicId())
                .orElseThrow(() -> new SellerNotFoundException("셀러를 찾을 수 없습니다: " + request.sellerPublicId()));
        ProductRegistrationResponse registered =
                productRegistrationService.registerProduct(seller.getId(), request.toRegistrationRequest());
        Product product = productRepository.findByPublicId(registered.productPublicId())
                .orElseThrow(() -> new IllegalStateException("등록 직후 상품 재조회 실패: " + registered.productPublicId()));
        product.applySaleTerms(
                request.supplyPrice(), toKstLocalDateTime(request.saleStartAt()), toKstLocalDateTime(request.saleEndAt()));
        auditRecorder.record(auditContext, AuditLogAction.CREATE, PolymorphicTargetType.PRODUCT, product.getId(),
                Map.of(), snapshot(product));
        log.info("[AdminProduct] 등록 완료 sellerPublicId={} productPublicId={}", request.sellerPublicId(), product.getPublicId());
        return registered;
    }

    /**
     * 기본정보 수정(전체 치환). categoryId 미존재 404.
     *
     * @throws ProductNotFoundException 미존재·삭제(404)
     * @throws CategoryNotFoundException categoryId 미존재(404)
     * @throws IllegalArgumentException 판매 시작 ≥ 종료(400)
     */
    public Product update(String publicId, AdminProductUpdateRequest request, AuditContext auditContext) {
        Product product = findForUpdate(publicId);
        if (!categoryRepository.existsById(request.categoryId())) {
            throw new CategoryNotFoundException("상품 카테고리가 존재하지 않습니다: categoryId=" + request.categoryId());
        }
        Map<String, Object> before = snapshot(product);
        product.updateBasicInfo(
                request.categoryId(),
                request.name(),
                request.description(),
                request.basePrice(),
                request.supplyPrice(),
                request.thumbnailUrl(),
                toKstLocalDateTime(request.saleStartAt()),
                toKstLocalDateTime(request.saleEndAt()));
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.PRODUCT, product.getId(),
                before, snapshot(product));
        log.info("[AdminProduct] 기본정보 수정 publicId={}", publicId);
        return product;
    }

    /**
     * 이미지 메타 전체 치환. 목록 순서=display_order, 미포함 기존 이미지 soft-delete, 대표는 GALLERY 1장 이하.
     *
     * @throws ProductNotFoundException 상품 미존재(404)
     * @throws ProductImageNotFoundException imageId가 이 상품의 활성 이미지가 아닐 때(404)
     * @throws IllegalArgumentException 대표 2장 이상·DETAIL 대표 지정(400)
     * @throws com.zslab.mall.common.exception.MalformedRequestException 신규·변경 imageUrl이 서버 발급 상품 이미지 경로가 아닌 경우(400·D-174)
     */
    public void replaceImages(String publicId, AdminProductImagesRequest request, AuditContext auditContext) {
        Product product = findForUpdate(publicId);
        validateImages(request);

        Map<Long, ProductImage> existingById = productImageRepository.findByProductId(product.getId()).stream()
                .collect(Collectors.toMap(ProductImage::getId, Function.identity()));
        Map<String, Object> before = new HashMap<>();
        before.put("imageCount", existingById.size());
        before.put("thumbnailUrl", product.getThumbnailUrl());

        for (int order = 0; order < request.images().size(); order++) {
            AdminProductImagesRequest.Item item = request.images().get(order);
            ProductImageType imageType = ProductImageType.valueOf(item.imageType());
            if (item.imageId() == null) {
                ImageUploadService.requireServerIssuedProductUrl(item.imageUrl());
                productImageRepository.save(ProductImage.create(product, item.imageUrl(), imageType, order, item.main()));
                continue;
            }
            ProductImage existing = existingById.remove(item.imageId());
            if (existing == null) {
                throw new ProductImageNotFoundException(
                        "상품 이미지를 찾을 수 없습니다: productPublicId=" + publicId + ", imageId=" + item.imageId());
            }
            // D-174: 기존 행의 URL을 그대로 되돌려 보내는 편집(데모 시드 등 외부 URL 잔존 데이터)은 통과, URL을 바꾸는 경우만 서버 발급 경로 강제.
            if (!item.imageUrl().equals(existing.getImageUrl())) {
                ImageUploadService.requireServerIssuedProductUrl(item.imageUrl());
            }
            existing.updateMeta(item.imageUrl(), imageType, order, item.main());
        }
        // 목록에 남지 않은 기존 이미지는 soft-delete(FK RESTRICT·하드 삭제 금지·A5).
        existingById.values().forEach(ProductImage::markDeleted);
        // Track 77: 대표(GALLERY is_main) 이미지가 있으면 product.thumbnail_url을 그 썸네일 URL로 동기화한다(내부 업로드는 _thumb 역산·
        // 외부 URL은 원본 그대로). 대표가 없으면 기존 thumbnail_url을 유지한다(Track 59 결정3 "독립"을 대표 지정 시점에 한해 연결).
        request.images().stream()
                .filter(AdminProductImagesRequest.Item::main)
                .findFirst()
                .ifPresent(mainImage -> product.changeThumbnailUrl(imageUploadService.thumbnailUrlFor(mainImage.imageUrl())));

        Map<String, Object> after = new HashMap<>();
        after.put("imageCount", request.images().size());
        after.put("thumbnailUrl", product.getThumbnailUrl());
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.PRODUCT, product.getId(),
                before, after);
        log.info("[AdminProduct] 이미지 메타 치환 publicId={} count={} deleted={}",
                publicId, request.images().size(), existingById.size());
    }

    /** 상품 단위 수동 품절 on/off. 같은 값이면 감사 로그는 diff 없음으로 skip된다(AuditRecorder). */
    public Product changeSoldOut(String publicId, boolean soldOut, AuditContext auditContext) {
        Product product = findForUpdate(publicId);
        boolean before = product.isSoldoutManual();
        product.changeSoldoutManual(soldOut);
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.PRODUCT, product.getId(),
                Map.of("soldoutManual", before), Map.of("soldoutManual", soldOut));
        log.info("[AdminProduct] 수동 품절 변경 publicId={} {} → {}", publicId, before, soldOut);
        return product;
    }

    /**
     * soft-delete. 주문 이력이 1건이라도 있으면 409(대안=판매중지).
     *
     * @throws ProductNotFoundException 미존재·이미 삭제(404)
     * @throws ProductHasOrderHistoryException 주문 이력 존재(409)
     */
    public void delete(String publicId, AuditContext auditContext) {
        Product product = findForUpdate(publicId);
        if (orderItemRepository.existsByProductId(product.getId())) {
            throw new ProductHasOrderHistoryException(
                    "주문 이력이 있는 상품은 삭제할 수 없습니다. 판매중지(sale-status STOPPED)로 처리하세요: " + publicId);
        }
        product.markDeleted();
        auditRecorder.record(auditContext, AuditLogAction.DELETE, PolymorphicTargetType.PRODUCT, product.getId(),
                Map.of("deleted", false), Map.of("deleted", true));
        log.info("[AdminProduct] soft-delete publicId={}", publicId);
    }

    private Product findForUpdate(String publicId) {
        return productRepository.findByPublicIdForUpdate(publicId)
                .orElseThrow(() -> new ProductNotFoundException("상품을 찾을 수 없습니다: publicId=" + publicId));
    }

    private static void validateImages(AdminProductImagesRequest request) {
        long mainCount = 0;
        for (AdminProductImagesRequest.Item item : request.images()) {
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

    /** 감사 diff용 필드맵(null 값은 Map.of가 거부하므로 HashMap). */
    private static Map<String, Object> snapshot(Product product) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("categoryId", product.getCategoryId());
        fields.put("name", product.getName());
        fields.put("basePrice", product.getBasePrice());
        fields.put("supplyPrice", product.getSupplyPrice());
        fields.put("thumbnailUrl", product.getThumbnailUrl());
        fields.put("saleStartAt", product.getSaleStartAt() != null ? product.getSaleStartAt().toString() : null);
        fields.put("saleEndAt", product.getSaleEndAt() != null ? product.getSaleEndAt().toString() : null);
        return fields;
    }

    static LocalDateTime toKstLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(KST).toLocalDateTime();
    }
}
