package com.zslab.mall.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.order.enums.OrderItemStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OrderItem} 도메인 메서드 검증: ORD-5 생성 가드·changeStatus 합법/불법 전이·markPaid.
 */
class OrderItemTest {

    private OrderItem ordered() {
        return OrderItem.create(10L, 20L, 30L, 2, 5_000L, 10_000L);
    }

    @Test
    @DisplayName("create: 정상 입력 → ORDERED·필드 설정")
    void create_valid() {
        OrderItem item = ordered();
        assertThat(item.getItemStatus()).isEqualTo(OrderItemStatus.ORDERED);
        assertThat(item.getProductId()).isEqualTo(10L);
        assertThat(item.getVariantId()).isEqualTo(20L);
        assertThat(item.getSellerId()).isEqualTo(30L);
        assertThat(item.getQuantity()).isEqualTo(2);
        assertThat(item.getUnitPrice()).isEqualTo(5_000L);
        assertThat(item.getTotalPrice()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("create: ORD-5 위반(total ≠ unit×qty) → IllegalArgumentException")
    void create_violatesOrd5() {
        assertThatThrownBy(() -> OrderItem.create(10L, 20L, 30L, 2, 5_000L, 9_999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORD-5");
    }

    @Test
    @DisplayName("create: 수량 1 미만 → IllegalArgumentException")
    void create_quantityBelowOne() {
        assertThatThrownBy(() -> OrderItem.create(10L, 20L, 30L, 0, 5_000L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create: 필수값 null → IllegalArgumentException")
    void create_nullRequired() {
        assertThatThrownBy(() -> OrderItem.create(null, 20L, 30L, 1, 5_000L, 5_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("changeStatus: 합법 전이(ORDERED→PAID) 적용")
    void changeStatus_legal() {
        OrderItem item = ordered();
        item.changeStatus(OrderItemStatus.PAID);
        assertThat(item.getItemStatus()).isEqualTo(OrderItemStatus.PAID);
    }

    @Test
    @DisplayName("changeStatus: 불법 전이(ORDERED→SHIPPING) → IllegalStateException")
    void changeStatus_illegal() {
        OrderItem item = ordered();
        assertThatThrownBy(() -> item.changeStatus(OrderItemStatus.SHIPPING))
                .isInstanceOf(IllegalStateException.class);
        assertThat(item.getItemStatus()).isEqualTo(OrderItemStatus.ORDERED);
    }

    @Test
    @DisplayName("markPaid: ORDERED → PAID")
    void markPaid() {
        OrderItem item = ordered();
        item.markPaid();
        assertThat(item.getItemStatus()).isEqualTo(OrderItemStatus.PAID);
    }
}
