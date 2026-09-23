package com.zslab.mall.order.controller.response;

import com.zslab.mall.reconciliation.controller.response.AdminReconciliationIssueResponse;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 주문 상세(Track 79 D-168). 주문자·배송지·결제 이력·항목(항목별 배송·클레임 이력)·미결제 취소 사유(audit)·가능 액션.
 *
 * @param cancelReasons 미결제 관리자 취소의 audit 기록(Claim 없는 경로·diff_json 파싱). 결제 후 취소 사유는 items[].claims에 있다.
 * @param reconciliationIssues 이 주문의 불일치(Track 104-2 D-216·해결 포함·최신순)
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
        List<String> actions,
        List<AdminReconciliationIssueResponse> reconciliationIssues) {

    public record Buyer(String userId, String name, String email) {
    }

    /**
     * pgTid·failureCode는 PG 대사·실패 원인 확인용(Track 89-A·결제 내역 화면을 주문 상세로 흡수).
     * refundedAmount는 이 결제에 대한 COMPLETED 환불 합(Track 96-1 D-202·C-12): PAID인데 refundedAmount == amount면 Refund→Payment CANCELLED
     * 자동 전이(D-113)가 유실된 상태라 화면이 경고·수동 취소 버튼을 조건부로 노출한다. 환불이 없으면 0.
     */
    public record PaymentRow(String paymentId, String method, String status, long amount, String pgProvider,
            String pgTid, String failureCode, LocalDateTime paidAt, LocalDateTime createdAt, long refundedAmount) {
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
     * Track 81-B(D-171) 추가: attachmentUrls(반품 사진 URL·순서 보존·없으면 빈 목록).
     * Track 83(D-177) 추가: originalOptionLabel·exchangeOptionLabel(EXCHANGE 원/교환 옵션 라벨·비교환 null).
     */
    public record ClaimRow(String claimId, String type, String status, String reasonCode, String reasonDetail,
            Long requestedBy, LocalDateTime requestedAt, LocalDateTime processedAt, boolean approvable,
            String rejectReasonCode, String rejectMemo, String refundStatus,
            String returnCarrier, String returnTrackingNo, LocalDateTime pickedUpAt, String inspectionResult, Boolean restock,
            List<String> attachmentUrls, String originalOptionLabel, String exchangeOptionLabel) {
    }

    public record CancelReason(String reasonCode, String reasonDetail, Long actorUserId, String actorRole,
            LocalDateTime recordedAt) {
    }
}
