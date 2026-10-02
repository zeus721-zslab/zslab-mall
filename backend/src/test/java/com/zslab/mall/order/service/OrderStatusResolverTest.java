package com.zslab.mall.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OrderStatusResolver} 평가 규칙([5]→[6]→[7] 후 CANCELLED 제외 품목의 진행 단계로 [4]→[3]→[2]·기본 PAID) 검증.
 */
class OrderStatusResolverTest {

    private final OrderStatusResolver resolver = new OrderStatusResolver();

    @Test
    @DisplayName("[5] 모든 CANCELLED → CANCELLED")
    void allCancelled() {
        assertThat(resolver.resolve(List.of(OrderItemStatus.CANCELLED, OrderItemStatus.CANCELLED)))
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("[6] 일부 CANCELLED + 나머지 ∈ {CONFIRMED,RETURNED,EXCHANGED} → PARTIAL_CANCEL")
    void partialCancel() {
        assertThat(resolver.resolve(List.of(OrderItemStatus.CANCELLED, OrderItemStatus.CONFIRMED)))
                .isEqualTo(OrderStatus.PARTIAL_CANCEL);
        assertThat(resolver.resolve(List.of(OrderItemStatus.CANCELLED, OrderItemStatus.RETURNED)))
                .isEqualTo(OrderStatus.PARTIAL_CANCEL);
        assertThat(resolver.resolve(List.of(OrderItemStatus.CANCELLED, OrderItemStatus.EXCHANGED)))
                .isEqualTo(OrderStatus.PARTIAL_CANCEL);
    }

    @Test
    @DisplayName("[7] 모든 ∈ {CONFIRMED,RETURNED,EXCHANGED} → CONFIRMED")
    void allConfirmedLike() {
        assertThat(resolver.resolve(List.of(OrderItemStatus.CONFIRMED, OrderItemStatus.CONFIRMED)))
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(resolver.resolve(List.of(OrderItemStatus.CONFIRMED, OrderItemStatus.RETURNED, OrderItemStatus.EXCHANGED)))
                .isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("[4] 모든 DELIVERED → DELIVERED")
    void allDelivered() {
        assertThat(resolver.resolve(List.of(OrderItemStatus.DELIVERED, OrderItemStatus.DELIVERED)))
                .isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("[3] 하나라도 SHIPPING → SHIPPING")
    void anyShipping() {
        assertThat(resolver.resolve(List.of(OrderItemStatus.PAID, OrderItemStatus.SHIPPING)))
                .isEqualTo(OrderStatus.SHIPPING);
        // [4]보다 우선하지 않음: DELIVERED 섞여도 전부 DELIVERED 아니면 SHIPPING
        assertThat(resolver.resolve(List.of(OrderItemStatus.DELIVERED, OrderItemStatus.SHIPPING)))
                .isEqualTo(OrderStatus.SHIPPING);
    }

    @Test
    @DisplayName("[2] 하나라도 PREPARING → PREPARING")
    void anyPreparing() {
        assertThat(resolver.resolve(List.of(OrderItemStatus.PAID, OrderItemStatus.PREPARING)))
                .isEqualTo(OrderStatus.PREPARING);
    }

    @Test
    @DisplayName("기본: 그 외(전부 PAID 등) → PAID")
    void defaultsToPaid() {
        assertThat(resolver.resolve(List.of(OrderItemStatus.PAID, OrderItemStatus.PAID)))
                .isEqualTo(OrderStatus.PAID);
        // CANCELLED + PAID(종결 아님) 혼재는 [6] 미충족 → 기본 PAID
        assertThat(resolver.resolve(List.of(OrderItemStatus.CANCELLED, OrderItemStatus.PAID)))
                .isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("평가 우선순위: [5]>[6], [6]>[7], [3]>[2]")
    void evaluationPrecedence() {
        // 전부 CANCELLED는 PARTIAL_CANCEL이 아닌 CANCELLED
        assertThat(resolver.resolve(List.of(OrderItemStatus.CANCELLED, OrderItemStatus.CANCELLED)))
                .isEqualTo(OrderStatus.CANCELLED);
        // CANCELLED + CONFIRMED 는 CONFIRMED가 아닌 PARTIAL_CANCEL
        assertThat(resolver.resolve(List.of(OrderItemStatus.CANCELLED, OrderItemStatus.CONFIRMED)))
                .isEqualTo(OrderStatus.PARTIAL_CANCEL);
        // PREPARING + SHIPPING 은 PREPARING이 아닌 SHIPPING
        assertThat(resolver.resolve(List.of(OrderItemStatus.PREPARING, OrderItemStatus.SHIPPING)))
                .isEqualTo(OrderStatus.SHIPPING);
    }

    @Test
    @DisplayName("혼합 조합(최종 점검 K1): CANCELLED 제외 품목의 진행 단계로 판정 — PAID로 퇴행하지 않음")
    void mixedItems_resolveByStage() {
        assertThat(resolver.resolve(List.of(OrderItemStatus.CANCELLED, OrderItemStatus.DELIVERED)))
                .isEqualTo(OrderStatus.DELIVERED);
        assertThat(resolver.resolve(List.of(OrderItemStatus.DELIVERED, OrderItemStatus.CONFIRMED)))
                .isEqualTo(OrderStatus.DELIVERED);
        assertThat(resolver.resolve(List.of(OrderItemStatus.RETURN_REQUESTED)))
                .isEqualTo(OrderStatus.DELIVERED);
        assertThat(resolver.resolve(List.of(OrderItemStatus.EXCHANGE_REQUESTED, OrderItemStatus.DELIVERED)))
                .isEqualTo(OrderStatus.DELIVERED);
        assertThat(resolver.resolve(List.of(OrderItemStatus.DELIVERED, OrderItemStatus.PAID)))
                .isEqualTo(OrderStatus.SHIPPING);
        assertThat(resolver.resolve(List.of(OrderItemStatus.CANCELLED, OrderItemStatus.SHIPPING)))
                .isEqualTo(OrderStatus.SHIPPING);
        assertThat(resolver.resolve(List.of(OrderItemStatus.CANCELLED, OrderItemStatus.PAID)))
                .isEqualTo(OrderStatus.PAID);
        assertThat(resolver.resolve(List.of(OrderItemStatus.CANCEL_REQUESTED, OrderItemStatus.PREPARING)))
                .isEqualTo(OrderStatus.PREPARING);
        // 3단계 이상이 하나라도 있으면 PREPARING보다 SHIPPING
        assertThat(resolver.resolve(List.of(OrderItemStatus.CONFIRMED, OrderItemStatus.PREPARING)))
                .isEqualTo(OrderStatus.SHIPPING);
        assertThat(resolver.resolve(List.of(OrderItemStatus.RETURNED, OrderItemStatus.PAID)))
                .isEqualTo(OrderStatus.SHIPPING);
        // 취소 요청은 결제 완료 단계
        assertThat(resolver.resolve(List.of(OrderItemStatus.CANCEL_REQUESTED)))
                .isEqualTo(OrderStatus.PAID);
        // 일부 취소 + 확정·배송완료 혼재는 [6]이 아니라 DELIVERED
        assertThat(resolver.resolve(List.of(OrderItemStatus.CANCELLED, OrderItemStatus.CONFIRMED, OrderItemStatus.DELIVERED)))
                .isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("빈 입력·null → IllegalArgumentException")
    void emptyOrNull_throws() {
        assertThatThrownBy(() -> resolver.resolve(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
