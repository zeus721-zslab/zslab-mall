package com.zslab.mall.order.controller.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 주문 상세(Track 79 D-168). 주문자·배송지·결제 이력·항목(항목별 배송·클레임 이력)·미결제 취소 사유(audit)·가능 액션.
 *
 * @param cancelReasons 미결제 관리자 취소의 audit 기록(Claim 없는 경로·diff_json 파싱). 결제 후 취소 사유는 items[].claims에 있다.
 */
public record AdminOrderDetailResponse(
        String orderId,
        String orderNo,
        LocalDateTime orderedAt,
        LocalDateTime paidAt,
        String status,
        Buyer buyer,
        ShippingAddressResponse shippingAddress,
        long totalPrice,
        long discountAmount,
        long shippingFee,
        long paymentAmount,
        List<PaymentRow> payments,
        List<Item> items,
        List<CancelReason> cancelReasons,
        List<String> actions) {

    public record Buyer(String userId, String name, String email) {
    }

    public record PaymentRow(String paymentId, String method, String status, long amount, String pgProvider,
            LocalDateTime paidAt, LocalDateTime createdAt) {
    }

    public record Item(String orderItemId, String productName, String optionLabel, int quantity, long unitPrice,
            long totalPrice, String status, String sellerName, DeliveryRow delivery, List<ClaimRow> claims) {
    }

    public record DeliveryRow(String deliveryId, String carrier, String trackingNo, String status,
            LocalDateTime shippedAt, LocalDateTime deliveredAt) {
    }

    /**
     * {@code approvable}은 사용자 승인형 Claim(REQUESTED)에 대해 관리자 승인/거절 버튼 노출 여부(기존 단건 API 재사용).
     * Track 80(D-169) 추가: rejectReasonCode·rejectMemo(거부 전 null)·refundStatus(최신 환불 상태·환불 미생성 시 null).
     * Track 81-A(D-170) 추가: 반품 회수 송장(returnCarrier·returnTrackingNo)·pickedUpAt·검수 결과(inspectionResult)·재입고(restock).
     */
    public record ClaimRow(String claimId, String type, String status, String reasonCode, String reasonDetail,
            Long requestedBy, LocalDateTime requestedAt, LocalDateTime processedAt, boolean approvable,
            String rejectReasonCode, String rejectMemo, String refundStatus,
            String returnCarrier, String returnTrackingNo, LocalDateTime pickedUpAt, String inspectionResult, Boolean restock) {
    }

    public record CancelReason(String reasonCode, String reasonDetail, Long actorUserId, String actorRole,
            LocalDateTime recordedAt) {
    }
}
