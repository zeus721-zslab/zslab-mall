package com.zslab.mall.order.repository;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.user.entity.User;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDateTime;
import org.springframework.data.jpa.domain.Specification;

/**
 * 관리자 주문 목록 필터(Track 79 D-168·{@code AdminProductSpecifications} 패턴). 각 조건은 null이면 조건 없음. 검색어는 주문번호
 * 정확일치·주문자 이름/이메일 부분일치·품목 상품명 스냅샷 부분일치 OR이며, 타 Aggregate(User·OrderItem·Payment·Delivery)는 서브쿼리로
 * 참조한다(JPQL 파라미터 바인딩·SQL injection 위험 없음).
 */
public final class AdminOrderSpecifications {

    private AdminOrderSpecifications() {
    }

    /**
     * 목록 페이지 쿼리에서 shippingSnapshot(mappedBy OneToOne·LAZY 불가)을 함께 fetch해 주문별 스냅샷 SELECT(N+1)를 막는다.
     * count 쿼리(결과 타입 Long)에는 fetch를 걸지 않는다. 조건은 없으므로 null(무시)을 반환한다.
     */
    public static Specification<Order> fetchShippingSnapshot() {
        return (root, query, builder) -> {
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("shippingSnapshot", JoinType.LEFT);
            }
            return null;
        };
    }

    public static Specification<Order> status(OrderStatus status) {
        return (root, query, builder) -> status == null ? null : builder.equal(root.get("status"), status);
    }

    /** 주문일시(ordered_at) 범위. from·to 각각 null 허용(포함 경계). */
    public static Specification<Order> orderedBetween(LocalDateTime from, LocalDateTime to) {
        return (root, query, builder) -> {
            if (from == null && to == null) {
                return null;
            }
            if (from == null) {
                return builder.lessThanOrEqualTo(root.get("orderedAt"), to);
            }
            if (to == null) {
                return builder.greaterThanOrEqualTo(root.get("orderedAt"), from);
            }
            return builder.between(root.get("orderedAt"), from, to);
        };
    }

    /** 결제 상태: 해당 status의 payment 행이 1건 이상 있는 주문. */
    public static Specification<Order> paymentStatus(PaymentStatus paymentStatus) {
        return (root, query, builder) -> {
            if (paymentStatus == null) {
                return null;
            }
            Subquery<Long> payments = query.subquery(Long.class);
            Root<Payment> payment = payments.from(Payment.class);
            payments.select(payment.get("orderId"))
                    .where(builder.equal(payment.get("status"), paymentStatus));
            return root.get("id").in(payments);
        };
    }

    /** 배송 상태: 해당 status의 delivery가 달린 품목을 1건 이상 가진 주문. */
    public static Specification<Order> deliveryStatus(DeliveryStatus deliveryStatus) {
        return (root, query, builder) -> {
            if (deliveryStatus == null) {
                return null;
            }
            Subquery<Long> items = query.subquery(Long.class);
            Root<OrderItem> item = items.from(OrderItem.class);
            Root<Delivery> delivery = items.from(Delivery.class);
            items.select(item.get("order").get("id"))
                    .where(builder.equal(delivery.get("orderItemId"), item.get("id")),
                            builder.equal(delivery.get("direction"), DeliveryDirection.OUTBOUND), // 반품 회수 제외(Track 81-A)
                            builder.equal(delivery.get("status"), deliveryStatus));
            return root.get("id").in(items);
        };
    }

    /** 검색: 주문번호 정확일치 OR 주문자 이름/이메일 부분일치 OR 품목 상품명 스냅샷 부분일치. */
    public static Specification<Order> keyword(String likePattern, String rawKeyword) {
        return (root, query, builder) -> {
            if (likePattern == null) {
                return null;
            }
            Subquery<Long> buyers = query.subquery(Long.class);
            Root<User> user = buyers.from(User.class);
            buyers.select(user.get("id"))
                    .where(builder.or(
                            builder.like(user.get("name"), likePattern, '\\'),
                            builder.like(user.get("email"), likePattern, '\\')));

            Subquery<Long> orders = query.subquery(Long.class);
            Root<OrderItem> item = orders.from(OrderItem.class);
            orders.select(item.get("order").get("id"))
                    .where(builder.like(item.get("productName"), likePattern, '\\'));

            return builder.or(
                    builder.equal(root.get("orderNo"), rawKeyword),
                    root.get("buyerId").in(buyers),
                    root.get("id").in(orders));
        };
    }
}
