package com.zslab.mall.dashboard.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import java.time.LocalDateTime;

/** 최근 자기 품목 클레임 1행(요청 시각 내림차순). 구매자(requestedBy)·사유·첨부는 싣지 않는다. */
public record SellerDashboardRecentClaimResponse(
        String claimPublicId,
        ClaimType type,
        ClaimStatus status,
        String orderNo,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime requestedAt) {
}
