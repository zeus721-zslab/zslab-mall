package com.zslab.mall.claim.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.refund.enums.RefundStatus;
import java.time.LocalDateTime;

/**
 * 클레임 단건 상세 응답(D-89 Q7·OrderResponse 패턴 정합). 식별자는 전부 public_id·내부 BIGINT 미노출.
 *
 * <p>requestedBy(내부 buyerId)는 노출하지 않는다(Q7 보안·사용자 식별 누출 차단). reasonDetail은 본인 조회 시 노출한다(Q7 UX).
 * orderItemPublicId는 Claim.orderItemId(BIGINT)를 OrderItem.public_id로 해소해 채운다(enrich·OrderResponse 패턴 정합).
 *
 * <p>Track 80(D-169) 추가 필드: rejectReasonCode·rejectMemo(거부 전 null)·refundStatus(최신 환불 상태·환불 미생성 시 null).
 * 기존 필드는 무변경이다.
 */
public record ClaimResponse(
        String publicId,
        String orderItemPublicId,
        ClaimType claimType,
        ClaimStatus status,
        String reasonCode,
        String reasonDetail,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime requestedAt,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime processedAt,
        ClaimRejectReasonCode rejectReasonCode,
        String rejectMemo,
        RefundStatus refundStatus) {

    /** 영속 Claim + 해소된 orderItemPublicId로 상세 응답을 조립한다(환불 상태 미조회·요청 직후·전이 직후 응답용). */
    public static ClaimResponse from(Claim claim, String orderItemPublicId) {
        return from(claim, orderItemPublicId, null);
    }

    /** 영속 Claim + 해소된 orderItemPublicId + 최신 환불 상태로 상세 응답을 조립한다. */
    public static ClaimResponse from(Claim claim, String orderItemPublicId, RefundStatus refundStatus) {
        return new ClaimResponse(
                claim.getPublicId(),
                orderItemPublicId,
                claim.getType(),
                claim.getStatus(),
                claim.getReasonCode(),
                claim.getReasonDetail(),
                claim.getRequestedAt(),
                claim.getProcessedAt(),
                claim.getRejectReasonCode(),
                claim.getRejectMemo(),
                refundStatus);
    }
}
