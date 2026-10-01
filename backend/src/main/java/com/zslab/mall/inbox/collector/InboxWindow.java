package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxTab;
import com.zslab.mall.inbox.policy.InboxDeadlinePolicy;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 한 번의 수집 조건. {@code now}로 보류 만료·장기 배송 임계를, {@code today}(KST)로 탭 경계를 정한다.
 *
 * @param limit 유형별 최대 행 수(응답 전체 상한과 같다 — 병합 후 다시 자른다)
 */
public record InboxWindow(InboxTab tab, LocalDateTime now, LocalDate today, int limit) {

    public LocalDateTime tomorrowStart() {
        return InboxDeadlinePolicy.tomorrowStart(today);
    }
}
