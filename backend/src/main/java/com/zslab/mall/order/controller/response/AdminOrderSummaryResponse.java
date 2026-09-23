package com.zslab.mall.order.controller.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 주문 목록 행(Track 79 D-168). 결제·배송·클레임은 배치 enrich 결과이며 없으면 null/false다.
 *
 * @param paidAt         대표 결제 행(PAID)의 승인 시각. 미결제·실패·만료는 null(FE-27 보강·추가형 필드)
 * @param paymentAmount  실결제액 = total_price − discount_amount + shipping_fee(PaymentService.recomputeAmount 정합)
 * @param paymentStatus  PAID 행 우선, 없으면 최신 결제 행 상태·결제 행 없으면 null
 * @param deliveryStatus 품목 배송 집계: 배송 없음 null / 하나라도 SHIPPING → SHIPPING / 전부 DELIVERED → DELIVERED / 그 외 READY
 * @param allItemsReturned 전 품목 RETURNED(반품 완료) 여부. 이 주문은 규칙 [7]로 status가 CONFIRMED라 목록 보조 표기 근거(Track 103)
 * @param actions        관리자 가능 액션 코드(CANCEL·PREPARE_SHIPMENT·MARK_DELIVERED)
 */
public record AdminOrderSummaryResponse(
        String orderId,
        String orderNo,
        LocalDateTime orderedAt,
        LocalDateTime paidAt,
        String status,
        String buyerName,
        String buyerEmail,
        List<String> sellerNames,
        String productSummary,
        int itemCount,
        long paymentAmount,
        long shippingFee,
        String paymentMethod,
        String paymentStatus,
        String deliveryStatus,
        boolean claimInProgress,
        boolean allItemsReturned,
        List<String> actions) {
}
