package com.zslab.mall.stats.controller.response;

/**
 * 처리 소요시간 3구간(Track 88·D-182). 종결 시각(뒤 시각)이 기간에 속하는 건만 표본으로 삼는다(결제 코호트가 아님·최근 기간 편향 방지).
 * 클레임은 요청→종결(COMPLETED·REJECTED의 processed_at) — approve/complete/reject가 같은 processed_at을 덮어써 승인 시각은 소실되므로
 * "요청→승인"은 산출하지 않는다. 표본 0이면 해당 구간 null(전역 NON_NULL이라 생략).
 */
public record OrderLeadTimeResponse(
        LeadTimeMetricResponse paidToShipped,
        LeadTimeMetricResponse shippedToDelivered,
        LeadTimeMetricResponse claimRequestedToClosed) {
}
