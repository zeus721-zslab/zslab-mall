package com.zslab.mall.dashboard.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import java.time.LocalDateTime;

/** 최근 클레임 1행(요청 시각 내림차순). */
public record DashboardRecentClaimResponse(
        String claimPublicId,
        ClaimType type,
        ClaimStatus status,
        String orderNo,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime requestedAt) {
}
