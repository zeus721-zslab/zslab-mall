package com.zslab.mall.claim.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import java.time.LocalDateTime;

/**
 * 클레임 목록 항목 경량 응답(D-89 Q10·OrderSummaryResponse 패턴 정합). 페이로드 절감을 위해 필드를 한정한다.
 *
 * <p>reasonDetail·processedAt·orderItemPublicId는 단건 상세(ClaimResponse)에서만 노출한다(목록 N+1 회피).
 */
public record ClaimSummaryResponse(
        String publicId,
        ClaimType claimType,
        ClaimStatus status,
        String reasonCode,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime requestedAt) {

    /** 영속 Claim으로 목록 항목을 조립한다. */
    public static ClaimSummaryResponse from(Claim claim) {
        return new ClaimSummaryResponse(
                claim.getPublicId(),
                claim.getType(),
                claim.getStatus(),
                claim.getReasonCode(),
                claim.getRequestedAt());
    }
}
