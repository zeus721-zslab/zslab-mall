package com.zslab.mall.stats.controller.response;

/** 소요시간 통계 1구간(시간 단위·소수 2자리). 평균·중앙값은 서비스가 시각 쌍에서 계산한다(짝수 표본 중앙값 = 가운데 두 값 평균). */
public record LeadTimeMetricResponse(
        double avgHours,
        double medianHours,
        long count) {
}
