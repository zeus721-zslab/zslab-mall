package com.zslab.mall.inventory.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import java.time.LocalDateTime;

/**
 * 셀러 재고 목록 행(Track 90-C-1·variant 축). 식별자는 public_id(var_·prd_)만 노출하고 내부 PK는 싣지 않는다. optionLabel은
 * 옵션값 조합 표시 문자열("색상: 블랙 / 사이즈: M"·단순상품은 null). updatedAt은 재고 행(inventory) 갱신 시각이다.
 */
public record SellerInventorySummaryResponse(
        String variantPublicId,
        String productPublicId,
        String productName,
        String optionLabel,
        String sellerSku,
        int quantityOnHand,
        int quantityReserved,
        int quantityAvailable,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime updatedAt) {
}
