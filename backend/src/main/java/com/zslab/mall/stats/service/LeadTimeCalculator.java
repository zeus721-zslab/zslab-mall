package com.zslab.mall.stats.service;

import com.zslab.mall.stats.controller.response.LeadTimeMetricResponse;
import com.zslab.mall.stats.repository.TimePairProjection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 소요시간 평균·중앙값 계산(Track 88·D-182 α). Repository가 내린 시각 쌍(시작·종결)에서 초 단위 Duration을 만들어 서비스 메모리에서 계산한다
 * — 네이티브·윈도우 함수(MEDIAN OVER) 0 관례 유지·현 규모(발송 157·클레임 종결 19)에서 충분.
 *
 * <p>역전(종결 &lt; 시작)·NULL 쌍은 표본에서 제외하고 건수를 warn 로그로 남긴다(실측 역전 0이지만 방어). 표본 0이면 null.
 * 중앙값은 정렬 후 가운데 값·짝수 표본은 가운데 두 값의 평균. 시간 단위 소수 2자리.
 */
@Slf4j
final class LeadTimeCalculator {

    private static final double SECONDS_PER_HOUR = 3600.0;
    private static final double SCALE = 100.0;

    private LeadTimeCalculator() {
    }

    static LeadTimeMetricResponse calculate(String label, List<TimePairProjection> pairs) {
        List<Long> seconds = new ArrayList<>(pairs.size());
        int excluded = 0;
        for (TimePairProjection pair : pairs) {
            if (pair.getStartAt() == null || pair.getEndAt() == null || pair.getEndAt().isBefore(pair.getStartAt())) {
                excluded++;
                continue;
            }
            seconds.add(Duration.between(pair.getStartAt(), pair.getEndAt()).getSeconds());
        }
        if (excluded > 0) {
            log.warn("[Stats] 소요시간 표본 제외(역전·NULL): metric={} excluded={} total={}", label, excluded, pairs.size());
        }
        if (seconds.isEmpty()) {
            return null;
        }
        Collections.sort(seconds);
        double sum = 0;
        for (long value : seconds) {
            sum += value;
        }
        double average = sum / seconds.size();
        return new LeadTimeMetricResponse(toHours(average), toHours(median(seconds)), seconds.size());
    }

    private static double median(List<Long> sorted) {
        int size = sorted.size();
        int middle = size / 2;
        if (size % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    private static double toHours(double seconds) {
        return Math.round(seconds / SECONDS_PER_HOUR * SCALE) / SCALE;
    }
}
