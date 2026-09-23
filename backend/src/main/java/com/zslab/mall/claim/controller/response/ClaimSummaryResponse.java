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
 * 클레임 목록 항목 경량 응답(D-89 Q10·OrderSummaryResponse 패턴 정합). 페이로드 절감을 위해 필드를 한정한다.
 *
 * <p>reasonDetail·processedAt·orderItemPublicId·거부 메모는 단건 상세(ClaimResponse)에서만 노출한다(목록 N+1 회피). 환불 상태·
 * 주문번호·상품명은 페이지 단위 배치 조회로 채운다.
 */
public record ClaimSummaryResponse(
        String publicId,
        ClaimType claimType,
        ClaimStatus status,
        String reasonCode,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime requestedAt,
        ClaimRejectReasonCode rejectReasonCode,
        RefundStatus refundStatus,
        String orderNo,
        String productName) {

    /**
     * 영속 Claim + 최신 환불 상태(없으면 null) + 소속 주문·품목 정보로 목록 항목을 조립한다(Track 80 D-169·Track 101-B 주문 탭 통합).
     *
     * @param orderNo     주문번호(배치 projection·해소 실패 시 null)
     * @param productName 주문 시점 상품명 스냅샷(배치 조회·해소 실패 시 null)
     */
    public static ClaimSummaryResponse from(Claim claim, RefundStatus refundStatus, String orderNo, String productName) {
        return new ClaimSummaryResponse(
                claim.getPublicId(),
                claim.getType(),
                claim.getStatus(),
                claim.getReasonCode(),
                claim.getRequestedAt(),
                claim.getRejectReasonCode(),
                refundStatus,
                orderNo,
                productName);
    }
}
