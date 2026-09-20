package com.zslab.mall.order.repository;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import jakarta.persistence.criteria.Join;
import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.jpa.domain.Specification;

/**
 * 셀러 품목 목록 필터(Track 90-B-1·{@code AdminOrderSpecifications} 패턴). 루트는 {@link OrderItem}(셀러 주문 단위 = 자기 품목 행)이며
 * 주문 축 조건(결제일·주문번호·미결제 제외)은 {@code oi.order} 조인으로 건다. 각 조건은 null이면 조건 없음.
 * JPQL 파라미터 바인딩만 사용해 SQL injection 위험이 없다.
 */
public final class SellerOrderItemSpecifications {

    private SellerOrderItemSpecifications() {
    }

    /** 자기 품목만(order_item.seller_id·인덱스 ix_order_item_seller_status). */
    public static Specification<OrderItem> sellerId(Long sellerId) {
        return (root, query, builder) -> builder.equal(root.get("sellerId"), sellerId);
    }

    /**
     * 미결제 주문 제외. 결제 만료 주문의 품목은 {@code item_status=ORDERED}로 남으므로(품목 전이 없음·D-187 §1-A 3) 품목 상태만으로는
     * 걸러지지 않아 주문 상태 조인이 필수다. 셀러에게 미결제 주문은 아직 처리 대상이 아니다.
     */
    public static Specification<OrderItem> orderStatusNotIn(Collection<OrderStatus> excluded) {
        return (root, query, builder) -> builder.not(root.get("order").get("status").in(excluded));
    }

    /** 품목 public_id 정확일치(상세 조회·소유 조건과 AND로 결합해 타 셀러 품목은 미존재와 같은 404). */
    public static Specification<OrderItem> publicId(String publicId) {
        return (root, query, builder) -> builder.equal(root.get("publicId"), publicId);
    }

    public static Specification<OrderItem> itemStatus(OrderItemStatus status) {
        return (root, query, builder) -> status == null ? null : builder.equal(root.get("itemStatus"), status);
    }

    /** 결제일시(order.paid_at) 범위. from·to 각각 null 허용(포함 경계). */
    public static Specification<OrderItem> paidBetween(LocalDateTime from, LocalDateTime to) {
        return (root, query, builder) -> {
            if (from == null && to == null) {
                return null;
            }
            Join<OrderItem, Order> order = root.join("order");
            if (from == null) {
                return builder.lessThanOrEqualTo(order.get("paidAt"), to);
            }
            if (to == null) {
                return builder.greaterThanOrEqualTo(order.get("paidAt"), from);
            }
            return builder.between(order.get("paidAt"), from, to);
        };
    }

    /** 검색: 상품명 스냅샷 부분일치 OR 주문번호 정확일치. */
    public static Specification<OrderItem> keyword(String likePattern, String rawKeyword) {
        return (root, query, builder) -> {
            if (likePattern == null) {
                return null;
            }
            return builder.or(
                    builder.like(root.get("productName"), likePattern, '\\'),
                    builder.equal(root.get("order").get("orderNo"), rawKeyword));
        };
    }
}
