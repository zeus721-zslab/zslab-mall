package com.zslab.mall.inbox.collector;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.policy.LongShippingThreshold;
import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.order.entity.OrderItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 관리자·셀러 · 장기 배송중 — 원 주문 발송(OUTBOUND·claim_id 없음)이 SHIPPING인 채 발송 후 {@link LongShippingThreshold#DAYS}일 경과.
 * 교환 발송은 뺀다(D-248): 관리자는 클레임 후속 "교환 배송완료"와 겹치고, 셀러는 클레임 배송을 완료 처리할 수 없다(422).
 * 그래서 대시보드 장기 배송중 건수(교환 발송 포함)와 다를 수 있다. 셀러는 자기 품목(order_item.seller_id)만 본다.
 */
@Component
public class LongShippingInboxSource extends TimedInboxSource<Delivery> {

    public LongShippingInboxSource(EntityManager entityManager) {
        super(entityManager, Delivery.class, InboxItemType.LONG_SHIPPING);
    }

    @Override
    protected InboxSelection<LocalDateTime> select(Root<Delivery> root, CriteriaQuery<?> query, CriteriaBuilder builder,
            InboxViewer viewer, LocalDateTime now) {
        Root<OrderItem> item = query.from(OrderItem.class);
        List<Predicate> pending = new ArrayList<>(List.of(
                builder.equal(item.get("id"), root.get("orderItemId")),
                builder.equal(root.get("direction"), DeliveryDirection.OUTBOUND),
                builder.equal(root.get("status"), DeliveryStatus.SHIPPING),
                builder.isNull(root.get("claimId")),
                builder.lessThanOrEqualTo(root.get("shippedAt"), now.minusDays(LongShippingThreshold.DAYS))));
        if (viewer.isSeller()) {
            pending.add(builder.equal(item.get("sellerId"), viewer.sellerId()));
        }
        return new InboxSelection<>(builder.and(pending.toArray(Predicate[]::new)), root.get("shippedAt"),
                root.get("publicId"), item.get("productName"), item.get("order").get("orderNo"));
    }
}
