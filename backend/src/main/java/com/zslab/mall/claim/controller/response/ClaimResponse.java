package com.zslab.mall.claim.controller.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import java.time.LocalDateTime;

/**
 * 클레임 단건 상세 응답(D-89 Q7·OrderResponse 패턴 정합). 식별자는 전부 public_id·내부 BIGINT 미노출.
 *
 * <p>requestedBy(내부 buyerId)는 노출하지 않는다(Q7 보안·사용자 식별 누출 차단). reasonDetail은 본인 조회 시 노출한다(Q7 UX).
 * orderItemPublicId는 Claim.orderItemId(BIGINT)를 OrderItem.public_id로 해소해 채운다(enrich·OrderResponse 패턴 정합).
 */
public record ClaimResponse(
        String publicId,
        String orderItemPublicId,
        ClaimType claimType,
        ClaimStatus status,
        String reasonCode,
        String reasonDetail,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        LocalDateTime requestedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        LocalDateTime processedAt) {

    /** 영속 Claim + 해소된 orderItemPublicId로 상세 응답을 조립한다. */
    public static ClaimResponse from(Claim claim, String orderItemPublicId) {
        return new ClaimResponse(
                claim.getPublicId(),
                orderItemPublicId,
                claim.getType(),
                claim.getStatus(),
                claim.getReasonCode(),
                claim.getReasonDetail(),
                claim.getRequestedAt(),
                claim.getProcessedAt());
    }
}
