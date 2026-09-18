package com.zslab.mall.delivery.repository;

import com.zslab.mall.delivery.controller.request.AdminDeliveryScope;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.entity.OrderShippingSnapshot;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDateTime;
import org.springframework.data.jpa.domain.Specification;

/**
 * 관리자 배송 목록 필터(Track 89-B D-184·{@code AdminClaimSpecifications} 패턴). 각 조건은 null이면 조건 없음. Delivery는 OrderItem을
 * ID로만 참조하므로 타 Aggregate(OrderItem·Order·OrderShippingSnapshot)는 서브쿼리로 참조한다(JPQL 파라미터 바인딩·SQL injection 위험 없음).
 */
public final class AdminDeliverySpecifications {

    private AdminDeliverySpecifications() {
    }

    /** 조회 범위(단일 축·{@link AdminDeliveryScope}). ALL은 조건 없음. */
    public static Specification<Delivery> scope(AdminDeliveryScope scope) {
        return (root, query, builder) -> switch (scope) {
            case ORIGINAL -> builder.and(
                    builder.equal(root.get("direction"), DeliveryDirection.OUTBOUND),
                    builder.isNull(root.get("claimId")));
            case CLAIM_OUTBOUND -> builder.and(
                    builder.equal(root.get("direction"), DeliveryDirection.OUTBOUND),
                    builder.isNotNull(root.get("claimId")));
            case RETURN -> builder.equal(root.get("direction"), DeliveryDirection.RETURN);
            case ALL -> null;
        };
    }

    public static Specification<Delivery> status(DeliveryStatus status) {
        return (root, query, builder) -> status == null ? null : builder.equal(root.get("status"), status);
    }

    public static Specification<Delivery> carrier(DeliveryCarrier carrier) {
        return (root, query, builder) -> carrier == null ? null : builder.equal(root.get("carrier"), carrier);
    }

    /**
     * 발송일(shipped_at) 범위. from·to 각각 null 허용(포함 경계). 기준 시각을 shipped_at으로 둔 이유: 배송 행의 유일한 업무 시각(발송일)이고
     * 영속 행은 전부 non-null(READY는 prepare-shipment 단일 트랜잭션 안에서 SHIPPING으로 바뀌어 남지 않음)이며 목록 "발송일" 컬럼과 정합이다.
     */
    public static Specification<Delivery> shippedBetween(LocalDateTime from, LocalDateTime to) {
        return (root, query, builder) -> {
            if (from == null && to == null) {
                return null;
            }
            if (from == null) {
                return builder.lessThanOrEqualTo(root.get("shippedAt"), to);
            }
            if (to == null) {
                return builder.greaterThanOrEqualTo(root.get("shippedAt"), from);
            }
            return builder.between(root.get("shippedAt"), from, to);
        };
    }

    /** 검색: 송장번호 정확일치 OR 주문번호 정확일치 OR 배송지 수령인명 부분일치(주문 배송지 스냅샷) → 해당 품목의 배송. */
    public static Specification<Delivery> keyword(String likePattern, String rawKeyword) {
        return (root, query, builder) -> {
            if (likePattern == null) {
                return null;
            }
            Subquery<Long> ordersByRecipient = query.subquery(Long.class);
            Root<OrderShippingSnapshot> snapshot = ordersByRecipient.from(OrderShippingSnapshot.class);
            ordersByRecipient.select(snapshot.get("order").get("id"))
                    .where(builder.like(snapshot.get("recipientName"), likePattern, '\\'));

            Subquery<Long> items = query.subquery(Long.class);
            Root<OrderItem> item = items.from(OrderItem.class);
            items.select(item.get("id"))
                    .where(builder.or(
                            builder.equal(item.get("order").get("orderNo"), rawKeyword),
                            item.get("order").get("id").in(ordersByRecipient)));

            return builder.or(
                    builder.equal(root.get("trackingNo"), rawKeyword),
                    root.get("orderItemId").in(items));
        };
    }

    /** 송장번호 정확일치(자기 행 제외·송장 정정 중복 사전 검사용). */
    public static Specification<Delivery> trackingNoOfOther(String trackingNo, Long selfId) {
        return (root, query, builder) -> builder.and(
                builder.equal(root.get("trackingNo"), trackingNo),
                builder.notEqual(root.get("id"), selfId));
    }
}
