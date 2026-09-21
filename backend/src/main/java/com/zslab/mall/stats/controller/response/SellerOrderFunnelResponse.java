package com.zslab.mall.stats.controller.response;

/**
 * 셀러 결제 코호트 퍼널 3단계(Track 90-E-2·관리자 {@link OrderFunnelResponse}의 품목 단위 축소). 기간 내 결제(paid_at)된 주문의 자기 품목을
 * 코호트로 잡고 원 발송 delivery(OUTBOUND·claim_id NULL)의 shipped_at·delivered_at 도달을 센다(도달 시각이 기간 밖이어도 도달·부분 출고는 품목별).
 * 도달률은 FE가 계산한다(건수만 반환).
 */
public record SellerOrderFunnelResponse(
        long paidItems,
        long shippedItems,
        long deliveredItems) {
}
