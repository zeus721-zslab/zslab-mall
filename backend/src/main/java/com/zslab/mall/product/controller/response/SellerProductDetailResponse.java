package com.zslab.mall.product.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.product.enums.ProductImageType;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.enums.ProductVariantStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 셀러 상품 상세(Track 90-C-1·수정 화면용). 관리자 {@code AdminProductDetailResponse}에서 셀러 식별·공급가·판매기간을 뺀 형태다.
 * 이미지·옵션 그룹/값은 public_id가 없어 내부 id(imageId·optionGroupId·optionValueId)를 그대로 싣는다 — 90-C-2 수정 API가
 * 관리자와 동일하게 {@code imageId null=신규}·{@code optionGroupId} 지정 계약을 쓰기 때문이다. 재고는 variant별 3수치(on_hand·
 * reserved·available)를 함께 싣는다.
 */
public record SellerProductDetailResponse(
        String productPublicId,
        String name,
        String description,
        Long categoryId,
        String categoryName,
        ProductStatus status,
        long basePrice,
        String thumbnailUrl,
        boolean soldoutManual,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime createdAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime updatedAt,
        List<Image> images,
        List<OptionGroup> optionGroups,
        List<Variant> variants) {

    public record Image(Long imageId, String imageUrl, ProductImageType imageType, int displayOrder, boolean main) {
    }

    public record OptionGroup(Long optionGroupId, String name, int displayOrder, List<OptionValue> values) {
    }

    public record OptionValue(Long optionValueId, String value, int displayOrder) {
    }

    public record Variant(
            String variantPublicId,
            String variantCode,
            String sellerSku,
            String barcode,
            long additionalPrice,
            ProductVariantStatus status,
            boolean soldoutManual,
            int displayOrder,
            List<VariantOption> options,
            int quantityOnHand,
            int quantityReserved,
            int quantityAvailable) {
    }

    public record VariantOption(Long optionGroupId, Long optionValueId, String value) {
    }
}
