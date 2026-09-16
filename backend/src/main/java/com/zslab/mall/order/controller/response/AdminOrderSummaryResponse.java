package com.zslab.mall.order.controller.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 주문 목록 행(Track 79 D-168). 결제·배송·클레임은 배치 enrich 결과이며 없으면 null/false다.
 *
 * @param paymentAmount  실결제액 = total_price − discount_amount + shipping_fee(PaymentService.recomputeAmount 정합)
 * @param paymentStatus  PAID 행 우선, 없으면 최신 결제 행 상태·결제 행 없으면 null
 * @param deliveryStatus 품목 배송 집계: 배송 없음 null / 하나라도 SHIPPING → SHIPPING / 전부 DELIVERED → DELIVERED / 그 외 READY
 * @param actions        관리자 가능 액션 코드(CANCEL·PREPARE_SHIPMENT·MARK_DELIVERED)
 */
public record AdminOrderSummaryResponse(
        String orderId,
        String orderNo,
        LocalDateTime orderedAt,
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
        List<String> actions) {
}
