package com.zslab.mall.stats.service;

import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.stats.enums.StatsCompare;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 매출 통계 조회 기간(Track 87·D-181). 날짜(from·to·종료일 포함)를 받아 KST 반구간 {@code [start, end)}(= from 00:00 ~ to 익일 00:00)로
 * 바꾼다 — 대시보드 D-180과 같은 경계 규칙이며 대시보드 코드는 공유하지 않는다(회귀 방지·인라인 규칙만 동일).
 */
record StatsPeriod(LocalDate from, LocalDate to) {

    /**
     * @throws MalformedRequestException from이 to보다 늦을 때(400·관리자 주문 목록 관례)
     */
    static StatsPeriod of(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new MalformedRequestException("from은 to보다 늦을 수 없습니다.");
        }
        return new StatsPeriod(from, to);
    }

    LocalDateTime start() {
        return from.atStartOfDay();
    }

    LocalDateTime end() {
        return to.plusDays(1).atStartOfDay();
    }

    long dayCount() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** 비교 기간. PREVIOUS = 같은 일수만큼 직전(end가 현재 start와 맞닿음)·YEAR_AGO = 1년 전 같은 날짜 구간·NONE = null. */
    StatsPeriod compareWith(StatsCompare compare) {
        return switch (compare) {
            case NONE -> null;
            case PREVIOUS -> new StatsPeriod(from.minusDays(dayCount()), to.minusDays(dayCount()));
            case YEAR_AGO -> new StatsPeriod(from.minusYears(1), to.minusYears(1));
        };
    }
}
