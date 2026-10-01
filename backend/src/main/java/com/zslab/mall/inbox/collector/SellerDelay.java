package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.inbox.policy.InboxDeadlinePolicy;
import java.time.LocalDateTime;

/**
 * 셀러 1곳의 지연(D-252 정의 확정표) — 유형별 기한 초과 건수와 그중 가장 오래된 건의 기준 시각.
 *
 * @param deliveryReadyOldestBase      초과 발송 대기 중 가장 이른 결제 시각(0건이면 null)
 * @param questionUnansweredOldestBase 초과 Q&amp;A 미답변 중 가장 이른 작성 시각(0건이면 null)
 */
public record SellerDelay(long deliveryReadyCount, LocalDateTime deliveryReadyOldestBase, long questionUnansweredCount,
        LocalDateTime questionUnansweredOldestBase) {

    static final SellerDelay NONE = new SellerDelay(0, null, 0, null);

    public boolean hasDelay() {
        return deliveryReadyCount > 0 || questionUnansweredCount > 0;
    }

    /** 가장 오래된 초과 건의 기한(초과 없음 null). 유형마다 기한 간격이 달라도 맞도록 기한끼리 비교한다. */
    public LocalDateTime oldestDueAt() {
        LocalDateTime deliveryDue = dueAt(InboxItemType.DELIVERY_READY, deliveryReadyOldestBase);
        LocalDateTime questionDue = dueAt(InboxItemType.QUESTION_UNANSWERED, questionUnansweredOldestBase);
        if (deliveryDue == null) {
            return questionDue;
        }
        if (questionDue == null) {
            return deliveryDue;
        }
        return deliveryDue.isAfter(questionDue) ? questionDue : deliveryDue;
    }

    /** {@link #oldestDueAt()} 건의 기준 시각. */
    public LocalDateTime oldestBaseAt() {
        LocalDateTime oldestDue = oldestDueAt();
        if (oldestDue == null) {
            return null;
        }
        return oldestDue.equals(dueAt(InboxItemType.DELIVERY_READY, deliveryReadyOldestBase))
                ? deliveryReadyOldestBase
                : questionUnansweredOldestBase;
    }

    private static LocalDateTime dueAt(InboxItemType type, LocalDateTime base) {
        return base == null ? null : base.plus(InboxDeadlinePolicy.timedDeadline(type));
    }
}
