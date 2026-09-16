package com.zslab.mall.product.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.product.enums.ProductImageType;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.enums.ProductVariantStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 상품 상세(수정 화면용·Track 76). 카탈로그 상세와 달리 내부 id(이미지·옵션 그룹/값)를 노출한다 — 수정 요청이 그 id로 대상을
 * 지정하기 때문이다(관리자 전용·BUYER 미노출). 단순상품의 DEFAULT sentinel 옵션 그룹은 카탈로그와 동일하게 숨기며, 그 variant의
 * options는 빈 목록이다.
 */
public record AdminProductDetailResponse(
        String productPublicId,
        String name,
        String description,
        Long categoryId,
        String categoryName,
        String sellerPublicId,
        String sellerName,
        ProductStatus status,
        boolean soldOutManual,
        long basePrice,
        Long supplyPrice,
        String thumbnailUrl,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime saleStartAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime saleEndAt,
        List<Image> images,
        List<OptionGroup> optionGroups,
        List<Variant> variants) {

    public record Image(Long imageId, String imageUrl, ProductImageType imageType, int displayOrder, boolean main) {
    }

    public record OptionGroup(Long optionGroupId, String name, int displayOrder, List<OptionValue> values) {
    }

    public record OptionValue(Long optionValueId, String value, int displayOrder) {
    }

    /** quantityAvailable·quantityOnHand는 inventory 행 부재 시 0. options는 (그룹·값) 조합·DEFAULT sentinel 제외. */
    public record Variant(
            String variantPublicId,
            String variantCode,
            String sellerSku,
            String barcode,
            long additionalPrice,
            ProductVariantStatus status,
            boolean soldOutManual,
            int displayOrder,
            int quantityAvailable,
            int quantityOnHand,
            List<VariantOption> options) {
    }

    public record VariantOption(Long optionGroupId, String groupName, Long optionValueId, String value) {
    }
}
