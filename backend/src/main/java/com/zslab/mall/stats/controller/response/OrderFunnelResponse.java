package com.zslab.mall.stats.controller.response;

/**
 * 결제 코호트 퍼널(Track 88·D-182). 기간 내 결제(paid_at)된 주문의 품목을 코호트로 잡고 각 단계 도달 여부를 milestone 컬럼으로 센다
 * (도달 시각이 기간 밖이어도 도달·"그 기간에 결제된 품목이 결국 어디까지 갔는가"). 발송·배송완료는 원 발송 delivery(OUTBOUND·claim_id NULL),
 * 구매확정은 order_item.confirmed_at. cancelled·returned는 취소·반품 클레임으로 종결된 품목(item_status)이며 교환은 이탈이 아니다(DELIVERED 복귀).
 * 도달률·이탈률은 FE가 계산한다(건수만 반환·D-180/181 일관).
 */
public record OrderFunnelResponse(
        long paidItems,
        long shippedItems,
        long deliveredItems,
        long confirmedItems,
        long cancelledItems,
        long returnedItems) {
}
