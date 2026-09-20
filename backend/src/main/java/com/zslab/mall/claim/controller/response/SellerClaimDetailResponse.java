package com.zslab.mall.claim.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.SellerOrderItemOrderProjection;
import com.zslab.mall.refund.enums.RefundStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 셀러 클레임 상세(Track 90-D-1). 관리자에는 상세 GET이 없어 이 record가 원본이다: 목록 행 + 첨부 목록 + 교환품 배송 상태.
 * 첨부는 public_id·URL만 싣는다 — {@code attachment.file_name}은 구매자 기기의 원본 파일명이라 응답에 넣지 않는다.
 * 거부 사유는 코드({@code rejectReasonCode})만 싣고 거부 메모({@code rejectMemo})는 싣지 않는다(목록 행에는 둘 다 없다).
 *
 * @param rejectReasonCode       거부 사유 코드(거부 전 null)
 * @param attachments            반품 사진(순서 보존·없으면 빈 목록)
 * @param exchangeDeliveryStatus 교환품 발송(OUTBOUND·claim_id) 최신 배송 상태(EXCHANGE 외·미발송 null·송장 등 상세 없음)
 */
public record SellerClaimDetailResponse(
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
        ClaimRejectReasonCode rejectReasonCode,
        long attachmentCount,
        List<AttachmentRow> attachments,
        DeliveryStatus exchangeDeliveryStatus) {

    /** 첨부 1건(public_id·서빙 URL만). */
    public record AttachmentRow(String attachmentId, String url) {

        public static AttachmentRow from(Attachment attachment) {
            return new AttachmentRow(attachment.getPublicId(), attachment.getFilePath());
        }
    }

    public static SellerClaimDetailResponse of(Claim claim, OrderItem item, SellerOrderItemOrderProjection order,
            RefundStatus refundStatus, List<Attachment> attachments, DeliveryStatus exchangeDeliveryStatus) {
        return new SellerClaimDetailResponse(claim.getPublicId(), claim.getType(), claim.getStatus(), claim.getRequestedAt(),
                claim.getProcessedAt(),
                order == null ? null : order.getOrderNo(),
                item == null ? null : item.getProductName(),
                item == null ? null : item.getOptionLabel(),
                claim.getReasonCode(), claim.getReasonDetail(), refundStatus, claim.getRejectReasonCode(), attachments.size(),
                attachments.stream().map(AttachmentRow::from).toList(), exchangeDeliveryStatus);
    }
}
