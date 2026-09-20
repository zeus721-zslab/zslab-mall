package com.zslab.mall.order.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import java.time.LocalDateTime;

/**
 * 셀러 품목 행의 클레임 요약(Track 90-D-1·최신 1건). claim:order_item = 1:N(거부·종결 후 재청구는 새 행)이라 요청일 최신 1건만 싣고 건수는
 * 품목 행의 {@code claimCount}가 든다. 사유·구매자·첨부는 싣지 않는다(상세는 {@code GET /api/v1/seller/claims/{clm}}).
 */
public record SellerOrderItemClaimResponse(
        String claimId,
        ClaimType type,
        ClaimStatus status,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime requestedAt) {

    public static SellerOrderItemClaimResponse from(Claim claim) {
        if (claim == null) {
            return null;
        }
        return new SellerOrderItemClaimResponse(claim.getPublicId(), claim.getType(), claim.getStatus(), claim.getRequestedAt());
    }
}
