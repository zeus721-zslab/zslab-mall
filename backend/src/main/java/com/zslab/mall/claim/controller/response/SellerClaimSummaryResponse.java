package com.zslab.mall.claim.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.SellerOrderItemOrderProjection;
import com.zslab.mall.refund.enums.RefundStatus;
import java.time.LocalDateTime;

/**
 * 셀러 클레임 목록 행(Track 90-D-1). 관리자 {@code AdminClaimSummaryResponse}와 별도 record다 — 관리자 DTO를 재사용하면 필드가 늘어날 때
 * 셀러에게 조용히 샌다. 셀러 노출 금지(구매자 이름·이메일·orderId·처리 액션·거부 메모·환불 금액·첨부 원본 파일명)는 애초에 필드가 없다.
 * 응답 키 집합은 통합 테스트가 화이트리스트로 고정한다.
 *
 * @param claimId         클레임 public_id(clm_)
 * @param orderNo         주문번호(주문 축 표시값은 번호뿐)
 * @param refundStatus    최신 환불 상태(환불 미생성 시 null·금액 없음)
 * @param attachmentCount 반품 사진 첨부 개수(URL은 상세에서)
 */
public record SellerClaimSummaryResponse(
        String claimId,
        ClaimType type,
        ClaimStatus status,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime requestedAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime processedAt,
        String orderNo,
        String productName,
        String optionLabel,
        String reasonCode,
        String reasonDetail,
        RefundStatus refundStatus,
        long attachmentCount) {

    public static SellerClaimSummaryResponse of(Claim claim, OrderItem item, SellerOrderItemOrderProjection order,
            RefundStatus refundStatus, long attachmentCount) {
        return new SellerClaimSummaryResponse(claim.getPublicId(), claim.getType(), claim.getStatus(), claim.getRequestedAt(),
                claim.getProcessedAt(),
                order == null ? null : order.getOrderNo(),
                item == null ? null : item.getProductName(),
                item == null ? null : item.getOptionLabel(),
                claim.getReasonCode(), claim.getReasonDetail(), refundStatus, attachmentCount);
    }
}
