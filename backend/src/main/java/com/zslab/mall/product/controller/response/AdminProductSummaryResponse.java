package com.zslab.mall.product.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.enums.SaleStopSource;
import java.time.LocalDateTime;

/**
 * 관리자 상품 목록 행(Track 76). soldOut은 {@code ProductPurchasePolicy.isSoldOut} 판정 결과(재고·수동품절 종합)이고 soldOutManual은
 * 상품 단위 수동 품절 스위치 값이다(FE가 "품절(수동)"·"품절(재고)"를 구분 표기). stockTotal은 활성 variant 가용재고 합이다.
 * saleEndAt null = 무기한, supplyPrice null = 미입력.
 */
public record AdminProductSummaryResponse(
        String productPublicId,
        String name,
        String thumbnailUrl,
        String sellerPublicId,
        String sellerName,
        Long categoryId,
        String categoryName,
        int stockTotal,
        ProductStatus status,
        SaleStopSource saleStopSource,
        boolean soldOut,
        boolean soldOutManual,
        long basePrice,
        Long supplyPrice,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime saleStartAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime saleEndAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime createdAt) {
}
