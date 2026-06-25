package com.zslab.mall.order.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OrderStatus} DDL 정합 검증.
 *
 * <p>OrderStatus는 canTransitionTo를 두지 않는다(ORD-2·Resolver 파생). 따라서 전이 매트릭스 테스트는 생략하고
 * 값 집합(8값) 드리프트만 가드한다.
 */
class OrderStatusTest {

    @Test
    @DisplayName("DDL 정합: 8값 보유")
    void hasEightValues() {
        assertThat(OrderStatus.values()).hasSize(8);
    }

    @Test
    @DisplayName("DDL 정합: 값 이름 집합 일치")
    void valueNamesMatchDdl() {
        assertThat(OrderStatus.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "PENDING_PAYMENT", "PAID", "PREPARING", "SHIPPING",
                        "DELIVERED", "CONFIRMED", "CANCELLED", "PARTIAL_CANCEL");
    }
}
