package com.zslab.mall.claim.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import com.zslab.mall.claim.enums.ClaimInspectionResult;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.refund.enums.RefundStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 클레임 단건 상세 응답(D-89 Q7·OrderResponse 패턴 정합). 식별자는 전부 public_id·내부 BIGINT 미노출.
 *
 * <p>requestedBy(내부 buyerId)는 노출하지 않는다(Q7 보안·사용자 식별 누출 차단). reasonDetail은 본인 조회 시 노출한다(Q7 UX).
 * orderItemPublicId는 Claim.orderItemId(BIGINT)를 OrderItem.public_id로 해소해 채운다(enrich·OrderResponse 패턴 정합).
 *
 * <p>Track 80(D-169) 추가 필드: rejectReasonCode·rejectMemo(거부 전 null)·refundStatus(최신 환불 상태·환불 미생성 시 null).
 * Track 81-A(D-170) 추가 필드: returnShipmentRequired(회수 송장 등록 가능 단계)·returnShipment(회수 Delivery)·pickedUpAt·inspectionResult.
 * Track 81-B(D-171) 추가 필드: attachmentUrls(반품 사진 URL·순서 보존·없으면 빈 목록).
 * FE-29 추가 필드: reshipment(검수 불합격 재발송 Delivery·OUTBOUND·claim_id·없으면 null).
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
        RefundStatus refundStatus,
        boolean returnShipmentRequired,
        ReturnShipmentResponse returnShipment,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime pickedUpAt,
        ClaimInspectionResult inspectionResult,
        List<String> attachmentUrls,
        ReturnShipmentResponse reshipment,
        /** 교환 요청 옵션 라벨(EXCHANGE·Track 83 D-177·미해소 null). 비교환은 null. */
        String exchangeOptionLabel,
        /** 교환 전 원 옵션 라벨(EXCHANGE·승인 스냅샷 우선·없으면 재조립·D-177 결정 2 보충). 비교환은 null. */
        String originalOptionLabel) {

    /** 영속 Claim + 해소된 orderItemPublicId로 상세 응답을 조립한다(환불 상태·회수 송장·첨부 미조회·전이 직후 응답용). */
    public static ClaimResponse from(Claim claim, String orderItemPublicId) {
        return from(claim, orderItemPublicId, null, null, List.of(), null);
    }

    /** 요청 직후 응답(Track 81-B): 환불 상태·회수 송장 없이 첨부 URL만 싣는다. */
    public static ClaimResponse from(Claim claim, String orderItemPublicId, List<String> attachmentUrls) {
        return from(claim, orderItemPublicId, null, null, attachmentUrls, null);
    }

    /** 영속 Claim + 해소된 orderItemPublicId + 최신 환불 상태로 상세 응답을 조립한다(회수 송장 미조회). */
    public static ClaimResponse from(Claim claim, String orderItemPublicId, RefundStatus refundStatus) {
        return from(claim, orderItemPublicId, refundStatus, null, List.of(), null);
    }

    /**
     * 영속 Claim + 환불 상태 + 회수 Delivery(Track 81-A)로 상세 응답을 조립한다. {@code returnShipmentRequired}는 구매자가 회수 송장을
     * 등록해야 하는 단계(RETURN·APPROVED·회수 송장 없음·미회수)인지다.
     */
    public static ClaimResponse from(Claim claim, String orderItemPublicId, RefundStatus refundStatus, Delivery returnDelivery,
            List<String> attachmentUrls, Delivery reshipment) {
        return from(claim, orderItemPublicId, refundStatus, returnDelivery, attachmentUrls, reshipment, null, null);
    }

    /** {@link #from(Claim, String, RefundStatus, Delivery, List, Delivery)} + 교환/원 옵션 라벨(Track 83 D-177). */
    public static ClaimResponse from(Claim claim, String orderItemPublicId, RefundStatus refundStatus, Delivery returnDelivery,
            List<String> attachmentUrls, Delivery reshipment, String exchangeOptionLabel, String originalOptionLabel) {
        // 회수 송장 등록 단계는 반품·교환 공통(D-177): APPROVED·회수 송장 없음·미회수
        boolean returnShipmentRequired = claim.getType().isPickupBased() && claim.getStatus() == ClaimStatus.APPROVED
                && returnDelivery == null && claim.getPickedUpAt() == null;
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
                refundStatus,
                returnShipmentRequired,
                returnDelivery == null ? null : ReturnShipmentResponse.from(returnDelivery),
                claim.getPickedUpAt(),
                claim.getInspectionResult(),
                attachmentUrls,
                reshipment == null ? null : ReturnShipmentResponse.from(reshipment),
                exchangeOptionLabel,
                originalOptionLabel);
    }
}
