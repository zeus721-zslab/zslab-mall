package com.zslab.mall.stats.service;

import com.zslab.mall.stats.enums.StatsUnit;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * 통계 추이 구간(버킷) 생성 공용 헬퍼(Track 88·D-182). Track 87 매출 통계 서비스의 private static 로직을 동작 무변경으로 옮겨
 * 주문·클레임·회원 통계가 같은 키 규칙을 쓰게 한다.
 *
 * <p>구간 키: 일 {@code yyyy-MM-dd}·월 {@code yyyy-MM}은 DB DATE_FORMAT 결과와 같고, 주는 DB {@code %x-%v}("2026-38"·ISO 월요일 시작)를
 * 응답 키 {@code yyyy-'W'ww}("2026-W38")·라벨 주 시작일(월요일)로 바꾼다. Java 쪽 주 키는 {@link IsoFields}로 만들어 DB와 같은 규칙이다.
 */
final class StatsBuckets {

    static final String DAY_PATTERN = "%Y-%m-%d";
    /** ISO-8601 주(월요일 시작·주 기준 연도). {@code %X-%V}(일요일 시작)는 쓰지 않는다. */
    static final String WEEK_PATTERN = "%x-%v";
    static final String MONTH_PATTERN = "%Y-%m";
    private static final int DAYS_PER_WEEK = 7;

    private StatsBuckets() {
    }

    /** 추이 구간 1개(dbKey = DATE_FORMAT 결과·key = 응답 키·label = 표기). */
    record Bucket(String dbKey, String key, String label) {
    }

    /** 조회 기간이 걸치는 구간 수(주·월은 부분 구간 포함). */
    static int count(StatsPeriod period, StatsUnit unit) {
        return switch (unit) {
            case DAY -> (int) period.dayCount();
            case WEEK -> (int) (ChronoUnit.DAYS.between(mondayOf(period.from()), mondayOf(period.to())) / DAYS_PER_WEEK) + 1;
            case MONTH -> (int) ChronoUnit.MONTHS.between(YearMonth.from(period.from()), YearMonth.from(period.to())) + 1;
        };
    }

    /** from이 속한 구간부터 count개. 주는 from이 속한 ISO 주의 월요일부터 7일씩. */
    static List<Bucket> of(LocalDate from, StatsUnit unit, int count) {
        List<Bucket> buckets = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            buckets.add(switch (unit) {
                case DAY -> {
                    String day = from.plusDays(offset).toString();
                    yield new Bucket(day, day, day);
                }
                case WEEK -> {
                    LocalDate monday = mondayOf(from).plusWeeks(offset);
                    int weekYear = monday.get(IsoFields.WEEK_BASED_YEAR);
                    int week = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                    yield new Bucket(String.format("%d-%02d", weekYear, week),
                            String.format("%d-W%02d", weekYear, week), monday.toString());
                }
                case MONTH -> {
                    String month = YearMonth.from(from).plusMonths(offset).toString();
                    yield new Bucket(month, month, month);
                }
            });
        }
        return buckets;
    }

    /** DB DATE_FORMAT 패턴(JPQL FUNCTION('DATE_FORMAT', col, :pattern) 바인딩용). */
    static String pattern(StatsUnit unit) {
        return switch (unit) {
            case DAY -> DAY_PATTERN;
            case WEEK -> WEEK_PATTERN;
            case MONTH -> MONTH_PATTERN;
        };
    }

    private static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
