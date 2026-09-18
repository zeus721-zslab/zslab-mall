package com.zslab.mall.stats.enums;

/**
 * 매출 통계 집계 단위(Track 87·D-181·요청 파라미터 전용·DB 컬럼 아님). WEEK는 ISO-8601(월요일 시작·주 기준 연도)이며 DB 키 패턴은
 * MariaDB DATE_FORMAT {@code %x-%v}, Java 키는 {@code IsoFields}로 맞춘다.
 */
public enum StatsUnit {
    DAY,
    WEEK,
    MONTH
}
