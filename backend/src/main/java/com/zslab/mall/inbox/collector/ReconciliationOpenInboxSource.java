package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.reconciliation.entity.ReconciliationIssue;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/** 관리자 · 정합성 불일치(OPEN) — 기준 detectedAt. 식별자는 기존 해소 API의 id다. detail은 JSON 원문이라 부제로 싣지 않는다. */
@Component
public class ReconciliationOpenInboxSource extends TimedInboxSource<ReconciliationIssue> {

    public ReconciliationOpenInboxSource(EntityManager entityManager) {
        super(entityManager, ReconciliationIssue.class, InboxItemType.RECONCILIATION_OPEN);
    }

    @Override
    protected InboxSelection<LocalDateTime> select(Root<ReconciliationIssue> root, CriteriaQuery<?> query,
            CriteriaBuilder builder, InboxViewer viewer, LocalDateTime now) {
        return new InboxSelection<>(builder.equal(root.get("status"), ReconciliationIssueStatus.OPEN), root.get("detectedAt"),
                root.<Long>get("id").cast(String.class), root.get("issueType"), builder.nullLiteral(String.class),
                root.get("id"));
    }
}
