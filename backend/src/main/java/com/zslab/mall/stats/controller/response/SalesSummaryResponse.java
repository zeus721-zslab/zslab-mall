package com.zslab.mall.stats.controller.response;

/**
 * 기간 매출 요약(Track 87·D-181). revenue·refund·netRevenue 정의는 D-180 대시보드와 동일(결제완료 paid_at 합·COMPLETED 환불 refunded_at 합·차).
 * itemQuantity는 결제완료 주문 품목 수량 합, avgOrderValue = revenue / orderCount(원·반올림·0건이면 0),
 * avgItemsPerOrder = itemQuantity / orderCount(소수 2자리·0건이면 0). 비교 기간도 같은 형태로 내리고 증감률은 FE가 계산한다.
 */
public record SalesSummaryResponse(
        long revenue,
        long refund,
        long netRevenue,
        long orderCount,
        long itemQuantity,
        long avgOrderValue,
        double avgItemsPerOrder) {

    private static final double ITEMS_PER_ORDER_SCALE = 100.0;

    public static SalesSummaryResponse of(long revenue, long refund, long orderCount, long itemQuantity) {
        long avgOrderValue = orderCount == 0 ? 0L : Math.round((double) revenue / orderCount);
        double avgItemsPerOrder = orderCount == 0 ? 0.0
                : Math.round((double) itemQuantity / orderCount * ITEMS_PER_ORDER_SCALE) / ITEMS_PER_ORDER_SCALE;
        return new SalesSummaryResponse(revenue, refund, revenue - refund, orderCount, itemQuantity, avgOrderValue,
                avgItemsPerOrder);
    }

    /**
     * 비교 기간 데이터 유무 판정(결제 주문·완료 환불 모두 0건이면 "없음" → 응답 null·0과 구분·D-181).
     * 이름을 {@code isXxx}로 두면 Jackson이 record 프로퍼티로 직렬화해 {@code "empty"} 필드가 새므로 getter 패턴을 피한다.
     */
    public boolean hasNoData() {
        return orderCount == 0 && refund == 0;
    }
}
