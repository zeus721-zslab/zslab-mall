package com.zslab.mall.cart.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.cart.exception.CartItemQuantityLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CartItem} 수량 상한(최종 점검 K3 · 입력 상한). 요청 DTO @Max를 거치지 않는 생성·수량 변경도 재담기 합산과 같은 예외로 막는다.
 */
class CartItemTest {

    private static final String VARIANT_PID = "var_0000000000000000000000CI01";

    @Test
    @DisplayName("create: 상한(999) 통과 · 1000 → CartItemQuantityLimitExceeded")
    void create_aboveMax_throws() {
        assertThat(CartItem.create(1L, 2L, VARIANT_PID, CartItem.MAX_QUANTITY).getQuantity()).isEqualTo(CartItem.MAX_QUANTITY);

        assertThatThrownBy(() -> CartItem.create(1L, 2L, VARIANT_PID, CartItem.MAX_QUANTITY + 1))
                .isInstanceOf(CartItemQuantityLimitExceededException.class);
    }

    @Test
    @DisplayName("changeQuantity: 상한(999) 통과 · 1000 → CartItemQuantityLimitExceeded·수량 보존")
    void changeQuantity_aboveMax_throws() {
        CartItem cartItem = CartItem.create(1L, 2L, VARIANT_PID, 1);
        cartItem.changeQuantity(CartItem.MAX_QUANTITY);
        assertThat(cartItem.getQuantity()).isEqualTo(CartItem.MAX_QUANTITY);

        assertThatThrownBy(() -> cartItem.changeQuantity(CartItem.MAX_QUANTITY + 1))
                .isInstanceOf(CartItemQuantityLimitExceededException.class);
        assertThat(cartItem.getQuantity()).isEqualTo(CartItem.MAX_QUANTITY);
    }
}
