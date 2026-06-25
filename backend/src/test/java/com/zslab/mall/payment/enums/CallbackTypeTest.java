package com.zslab.mall.payment.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CallbackType} enum 무결성(D-34). 값 집합·valueOf 정합을 검증한다.
 */
class CallbackTypeTest {

    @Test
    @DisplayName("3값 보유: SUCCESS·FAILURE·CANCEL")
    void hasThreeValues() {
        assertThat(CallbackType.values())
                .containsExactly(CallbackType.SUCCESS, CallbackType.FAILURE, CallbackType.CANCEL);
    }

    @Test
    @DisplayName("valueOf: 문자열 매핑 정합")
    void valueOf_mapsByName() {
        assertThat(CallbackType.valueOf("SUCCESS")).isEqualTo(CallbackType.SUCCESS);
        assertThat(CallbackType.valueOf("FAILURE")).isEqualTo(CallbackType.FAILURE);
        assertThat(CallbackType.valueOf("CANCEL")).isEqualTo(CallbackType.CANCEL);
    }
}
