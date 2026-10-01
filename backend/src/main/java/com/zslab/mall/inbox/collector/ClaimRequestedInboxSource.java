package com.zslab.mall.inbox.collector;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.order.entity.OrderItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/** 관리자 · 클레임 접수(REQUESTED) — 기준 requestedAt. */
@Component
public class ClaimRequestedInboxSource extends TimedInboxSource<Claim> {

    public ClaimRequestedInboxSource(EntityManager entityManager) {
        super(entityManager, Claim.class, InboxItemType.CLAIM_REQUESTED);
    }

    @Override
    protected InboxSelection<LocalDateTime> select(Root<Claim> root, CriteriaQuery<?> query, CriteriaBuilder builder,
            InboxViewer viewer, LocalDateTime now) {
        Root<OrderItem> item = query.from(OrderItem.class);
        return new InboxSelection<>(
                builder.and(builder.equal(item.get("id"), root.get("orderItemId")),
                        builder.equal(root.get("status"), ClaimStatus.REQUESTED)),
                root.get("requestedAt"), root.get("publicId"), item.get("productName"), item.get("order").get("orderNo"));
    }
}
