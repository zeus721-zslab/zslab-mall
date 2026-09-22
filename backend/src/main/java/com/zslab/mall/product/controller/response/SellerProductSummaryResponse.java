package com.zslab.mall.product.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.enums.SaleStopSource;
import java.time.LocalDateTime;

/**
 * 셀러 상품 목록 행(Track 90-C-1). 관리자 {@code AdminProductSummaryResponse}에서 셀러 식별(sellerPublicId·sellerName)·공급가·판매기간·
 * 재고 합계를 뺀 자기 상품 요약이다. 재고 수량은 재고 목록 API({@code GET /api/v1/seller/inventories}) 소관이라 싣지 않는다.
 */
public record SellerProductSummaryResponse(
        String productPublicId,
        String name,
        Long categoryId,
        String categoryName,
        ProductStatus status,
        SaleStopSource saleStopSource,
        long basePrice,
        String thumbnailUrl,
        int variantCount,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime createdAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime updatedAt) {
}
