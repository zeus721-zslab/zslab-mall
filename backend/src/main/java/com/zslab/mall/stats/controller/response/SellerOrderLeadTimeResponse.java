package com.zslab.mall.stats.controller.response;

/**
 * 셀러 처리 소요시간 2구간(Track 90-E-2·관리자 {@link OrderLeadTimeResponse}에서 클레임 요청→종결 제외). 종결 시각(뒤 시각)이 기간에 속하는 자기 품목
 * 원 발송만 표본이며 표본 0이면 해당 구간 null(전역 NON_NULL이라 생략). 평균·중앙값은 {@code LeadTimeCalculator}(짝수 표본 = 가운데 두 값 평균).
 */
public record SellerOrderLeadTimeResponse(
        LeadTimeMetricResponse paidToShipped,
        LeadTimeMetricResponse shippedToDelivered) {
}
