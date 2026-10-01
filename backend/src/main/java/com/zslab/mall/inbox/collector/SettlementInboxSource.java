package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.inbox.policy.InboxDeadlinePolicy;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.enums.SettlementStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 관리자 · 정산 확정(PENDING)·지급(CONFIRMED) 공통. 기준은 지급 예정일(날짜)이고 기한은 "예정일 − N일"의 하루 끝이다
 * ({@link InboxDeadlinePolicy#payDateDueAt}). 오늘 탭 ⇔ 예정일 − N ≤ 오늘 ⇔ 예정일 &lt; 오늘 + N + 1. 식별자는 기존 정산 API의 id다.
 */
public abstract class SettlementInboxSource extends CriteriaInboxSource<Settlement, LocalDate> {

    private final SettlementStatus status;
    private final int leadDays;

    protected SettlementInboxSource(EntityManager entityManager, InboxItemType type, SettlementStatus status) {
        super(entityManager, Settlement.class, type);
        this.status = status;
        this.leadDays = InboxDeadlinePolicy.payDateLeadDays(type);
    }

    @Override
    protected InboxSelection<LocalDate> select(Root<Settlement> root, CriteriaQuery<?> query, CriteriaBuilder builder,
            InboxViewer viewer, LocalDateTime now) {
        Subquery<String> sellerName = query.subquery(String.class);
        Root<Seller> seller = sellerName.from(Seller.class);
        sellerName.select(seller.get("companyName")).where(builder.equal(seller.get("id"), root.get("sellerId")));
        return new InboxSelection<>(builder.equal(root.get("status"), status), root.get("scheduledPayDate"),
                root.<Long>get("id").cast(String.class), sellerName, builder.nullLiteral(String.class), root.get("id"));
    }

    @Override
    protected LocalDate tabCutoff(InboxWindow window) {
        return window.today().plusDays(leadDays + 1L);
    }

    @Override
    protected LocalDateTime baseAtOf(LocalDate base) {
        return base.atStartOfDay();
    }

    @Override
    protected LocalDateTime dueAtOf(LocalDate base) {
        return InboxDeadlinePolicy.payDateDueAt(type(), base);
    }
}
