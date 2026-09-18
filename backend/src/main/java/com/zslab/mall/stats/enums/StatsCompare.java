package com.zslab.mall.stats.enums;

/**
 * 매출 통계 비교 기간(Track 87·D-181·요청 파라미터 전용). PREVIOUS = 조회 기간과 같은 일수만큼 직전 구간·YEAR_AGO = 1년 전 같은 구간.
 * BE는 비교 기간 값을 그대로 내리고 증감률은 계산하지 않는다(D-180과 동일).
 */
public enum StatsCompare {
    NONE,
    PREVIOUS,
    YEAR_AGO
}
