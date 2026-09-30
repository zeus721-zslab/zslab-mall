package com.zslab.mall.demoseed.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Random;

/**
 * 데모 질문·답변 시각(D-244 D2 α). 질문은 하한(상품·작성자 생성 시각 중 늦은 쪽)과 현재 사이 최근 {@link #SPREAD_DAYS}일에 흩고, 답변은
 * 질문 + {@link #ANSWER_DELAY_MIN}~{@link #ANSWER_DELAY_MAX} 사이로 두되 현재를 넘기지 않는다. 시각 기준은 백엔드 저장 기준(JVM KST).
 */
public final class DemoQuestionTimeline {

    static final int SPREAD_DAYS = 60;
    static final Duration QUESTION_END_MARGIN = Duration.ofHours(1);
    static final Duration ANSWER_DELAY_MIN = Duration.ofHours(1);
    static final Duration ANSWER_DELAY_MAX = Duration.ofDays(3);

    private DemoQuestionTimeline() {
    }

    /** 질문 작성 시각: [max(하한, 현재 - 60일), 현재 - 1시간) 구간(하한이 그보다 늦으면 [하한, 현재)). */
    public static LocalDateTime questionAt(LocalDateTime lowerBound, LocalDateTime now, Random random) {
        LocalDateTime start = later(lowerBound, now.minusDays(SPREAD_DAYS));
        LocalDateTime end = now.minus(QUESTION_END_MARGIN);
        if (!start.isBefore(end)) {
            end = now;
        }
        return start.plus(fraction(Duration.between(start, end), random));
    }

    /** 답변 시각: 질문 + 1시간~3일, 현재를 넘기지 않는다(남은 시간이 짧으면 그 안에서 줄인다). */
    public static LocalDateTime answerAt(LocalDateTime questionAt, LocalDateTime now, Random random) {
        Duration remaining = Duration.between(questionAt, now);
        Duration max = remaining.compareTo(ANSWER_DELAY_MAX) < 0 ? remaining : ANSWER_DELAY_MAX;
        Duration min = ANSWER_DELAY_MIN.compareTo(max.dividedBy(2)) < 0 ? ANSWER_DELAY_MIN : max.dividedBy(2);
        return questionAt.plus(min).plus(fraction(max.minus(min), random));
    }

    private static Duration fraction(Duration span, Random random) {
        if (span.isNegative() || span.isZero()) {
            return Duration.ZERO;
        }
        return Duration.ofNanos((long) (span.toNanos() * random.nextDouble()));
    }

    private static LocalDateTime later(LocalDateTime left, LocalDateTime right) {
        return left.isAfter(right) ? left : right;
    }
}
