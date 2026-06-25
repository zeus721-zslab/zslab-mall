package com.zslab.mall.order.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link Order} 도메인 메서드(addItem·attachSnapshot·markPaid·applyResolvedStatus) 및
 * X1(publicId 기반 equals/hashCode·base 위임·발견 항목 #1) 검증.
 */
class OrderTest {

    private Order newOrder() {
        return Order.create(100L, "20260625-ABCDEF", 0L, 3_000L);
    }

    private OrderItem newItem(long unitPrice, int quantity) {
        return OrderItem.create(10L, 20L, 30L, quantity, unitPrice, unitPrice * quantity);
    }

    @Test
    @DisplayName("addItem: total_price 누적·양측 연결")
    void addItem_accumulatesAndLinks() {
        Order order = newOrder();
        OrderItem item1 = newItem(5_000L, 2); // 10,000
        OrderItem item2 = newItem(3_000L, 1); // 3,000

        order.addItem(item1);
        order.addItem(item2);

        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getTotalPrice()).isEqualTo(13_000L);
        // 양측 연결: OrderItem.order(비공개)가 본 Order로 설정됨
        assertThat(ReflectionTestUtils.getField(item1, "order")).isSameAs(order);
        assertThat(ReflectionTestUtils.getField(item2, "order")).isSameAs(order);
    }

    @Test
    @DisplayName("attachSnapshot: 1:1 연결·양측 설정")
    void attachSnapshot_links() {
        Order order = newOrder();
        OrderShippingSnapshot snapshot = OrderShippingSnapshot.create(
                "홍길동", "010-1234-5678", "06236",
                "서울 강남대로 1", null, "101호", null);

        order.attachSnapshot(snapshot);

        assertThat(order.getShippingSnapshot()).isSameAs(snapshot);
        assertThat(ReflectionTestUtils.getField(snapshot, "order")).isSameAs(order);
    }

    @Test
    @DisplayName("markPaid: 모든 OrderItem PAID·status PAID·paid_at 설정 (규칙 [1])")
    void markPaid_appliesRuleOne() {
        Order order = newOrder();
        order.addItem(newItem(5_000L, 1));
        order.addItem(newItem(2_000L, 2));
        LocalDateTime paidAt = LocalDateTime.of(2026, 6, 25, 12, 0);

        order.markPaid(paidAt);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(paidAt);
        assertThat(order.getItems())
                .allSatisfy(item -> assertThat(item.getItemStatus()).isEqualTo(OrderItemStatus.PAID));
    }

    @Test
    @DisplayName("applyResolvedStatus: Resolver 결과 반영 (ORD-2)")
    void applyResolvedStatus_setsStatus() {
        Order order = newOrder();
        order.applyResolvedStatus(OrderStatus.SHIPPING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPING);
    }

    /**
     * X1: publicId 기반 equals/hashCode. AbstractPublicIdFullAuditableEntity가 onlyExplicitlyIncluded로
     * publicId만 포함하므로 Order/OrderItem은 재선언 없이 base 동작을 위임받는다(발견 항목 #1).
     */
    @Nested
    @DisplayName("X1: publicId 기반 equals/hashCode (base 위임)")
    class PublicIdEqualsHashCode {

        @Test
        @DisplayName("동일 publicId → equals true·hashCode 동일")
        void samePublicId_equal() {
            Order a = newOrder();
            Order b = newOrder();
            ReflectionTestUtils.setField(a, "publicId", "ord_0000000000000000000000SAME");
            ReflectionTestUtils.setField(b, "publicId", "ord_0000000000000000000000SAME");

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("다른 publicId → equals false")
        void differentPublicId_notEqual() {
            Order a = newOrder();
            Order b = newOrder();
            ReflectionTestUtils.setField(a, "publicId", "ord_000000000000000000000000AAA");
            ReflectionTestUtils.setField(b, "publicId", "ord_000000000000000000000000BBB");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("양쪽 publicId NULL → onlyExplicitlyIncluded 동작상 equal (구현 정합 확인)")
        void bothNullPublicId_equalByLombokSemantics() {
            Order a = newOrder();
            Order b = newOrder();
            // @PrePersist 전 상태: publicId 모두 null → 포함 필드(null) 동등 → equals true
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }
}
