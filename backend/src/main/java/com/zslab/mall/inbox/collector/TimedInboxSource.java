package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.inbox.policy.InboxDeadlinePolicy;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 기준 시각 + 고정 기간이 기한인 수집기({@link InboxDeadlinePolicy#timedDeadline}). 기한 &lt; 내일 00:00 ⇔ 기준 시각 &lt; 내일 00:00 − 기간.
 */
public abstract class TimedInboxSource<E> extends CriteriaInboxSource<E, LocalDateTime> {

    private final Duration deadline;

    protected TimedInboxSource(EntityManager entityManager, Class<E> rootType, InboxItemType type) {
        super(entityManager, rootType, type);
        this.deadline = InboxDeadlinePolicy.timedDeadline(type);
    }

    @Override
    protected LocalDateTime tabCutoff(InboxWindow window) {
        return window.tomorrowStart().minus(deadline);
    }

    @Override
    protected LocalDateTime baseAtOf(LocalDateTime base) {
        return base;
    }

    @Override
    protected LocalDateTime dueAtOf(LocalDateTime base) {
        return base.plus(deadline);
    }
}
