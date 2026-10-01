package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 셀러 · 발송 대기 — 자기 품목 PAID(송장 등록이 PAID→PREPARING→SHIPPING을 한 트랜잭션으로 전이하므로 PAID가 곧 대기). 기준은 주문 결제
 * 시각(품목에는 paidAt이 없다).
 */
@Component
public class DeliveryReadyInboxSource extends TimedInboxSource<OrderItem> {

    public DeliveryReadyInboxSource(EntityManager entityManager) {
        super(entityManager, OrderItem.class, InboxItemType.DELIVERY_READY);
    }

    @Override
    protected InboxSelection<LocalDateTime> select(Root<OrderItem> root, CriteriaQuery<?> query, CriteriaBuilder builder,
            InboxViewer viewer, LocalDateTime now) {
        return new InboxSelection<>(
                builder.and(builder.equal(root.get("sellerId"), viewer.sellerId()),
                        builder.equal(root.get("itemStatus"), OrderItemStatus.PAID)),
                root.get("order").get("paidAt"), root.get("publicId"), root.get("productName"),
                root.get("order").get("orderNo"));
    }
}
