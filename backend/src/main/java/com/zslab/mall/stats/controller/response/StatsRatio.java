package com.zslab.mall.stats.controller.response;

/** 통계 비율 공용 계산(Track 88·응답 record 팩토리와 서비스가 공유). 분자/분모 × 100을 소수 2자리로 반올림·분모 0이면 0(D-181 share 규약). */
public final class StatsRatio {

    private static final double PERCENT = 100.0;
    private static final double SCALE = 100.0;

    private StatsRatio() {
    }

    public static double percent(long numerator, long denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return Math.round(numerator * PERCENT / denominator * SCALE) / SCALE;
    }
}
