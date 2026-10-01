package com.zslab.mall.inbox.policy;

import com.zslab.mall.inbox.enums.InboxItemType;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

/**
 * 인박스 기한 정책(D-248 정의 확정표 단일 소스). 기한은 시간 기준이며 영업일을 적용하지 않는다.
 *
 * <p>시각형 유형은 기준 시각 + 기간이 기한이다. 정산 2종은 기준이 날짜(지급 예정일)라 "예정일 − N일"의 하루 끝(23:59:59.999 KST)을
 * 기한으로 둔다 — 00:00으로 두면 당일 건이 하루 종일 기한 초과로 뜬다. 재고 임박은 기한이 없다.
 */
public final class InboxDeadlinePolicy {

    /** 시각형 유형의 기준 시각 → 기한 간격. */
    private static final Map<InboxItemType, Duration> TIMED_DEADLINES = Map.of(
            InboxItemType.CLAIM_REQUESTED, Duration.ofHours(24),
            InboxItemType.CLAIM_FOLLOWUP, Duration.ofHours(48),
            InboxItemType.LONG_SHIPPING, Duration.ofDays(7),
            InboxItemType.INQUIRY_UNANSWERED, Duration.ofHours(24),
            InboxItemType.SELLER_REVIEW, Duration.ofHours(72),
            InboxItemType.PRODUCT_APPROVAL, Duration.ofHours(24),
            InboxItemType.RECONCILIATION_OPEN, Duration.ofHours(24),
            InboxItemType.DELIVERY_READY, Duration.ofHours(48),
            InboxItemType.QUESTION_UNANSWERED, Duration.ofHours(48));

    /** 정산 유형의 지급 예정일 → 기한일 앞당김 일수(확정 3일 전 · 지급 당일). */
    private static final Map<InboxItemType, Integer> PAY_DATE_LEAD_DAYS = Map.of(
            InboxItemType.SETTLEMENT_CONFIRM, 3,
            InboxItemType.SETTLEMENT_PAYOUT, 0);

    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59, 999_000_000);

    private InboxDeadlinePolicy() {
    }

    public static Duration timedDeadline(InboxItemType type) {
        Duration deadline = TIMED_DEADLINES.get(type);
        if (deadline == null) {
            throw new IllegalStateException("시각형 기한이 없는 유형입니다: " + type);
        }
        return deadline;
    }

    public static int payDateLeadDays(InboxItemType type) {
        Integer leadDays = PAY_DATE_LEAD_DAYS.get(type);
        if (leadDays == null) {
            throw new IllegalStateException("지급 예정일 기한이 없는 유형입니다: " + type);
        }
        return leadDays;
    }

    public static LocalDateTime payDateDueAt(InboxItemType type, LocalDate scheduledPayDate) {
        return scheduledPayDate.minusDays(payDateLeadDays(type)).atTime(END_OF_DAY);
    }

    /**
     * 오늘 탭 판정 경계. 기한이 이 시각 전이면 오늘 탭(경과 포함), 이후면 예정 탭이다.
     *
     * @param today 오늘(KST — JVM TZ Asia/Seoul)
     */
    public static LocalDateTime tomorrowStart(LocalDate today) {
        return today.plusDays(1).atStartOfDay();
    }
}
