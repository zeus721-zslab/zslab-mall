package com.zslab.mall.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link PhoneMasker} 단위 검증(Track 80 D-169·공용 유틸). */
class PhoneMaskerTest {

    @Test
    @DisplayName("mask: 하이픈·숫자만 형식 모두 010-****-1234로 앞 3·뒤 4만 노출")
    void mask_keepsPrefixAndSuffix() {
        assertThat(PhoneMasker.mask("010-1234-5678")).isEqualTo("010-****-5678");
        assertThat(PhoneMasker.mask("01012345678")).isEqualTo("010-****-5678");
        assertThat(PhoneMasker.mask("02 123 4567")).isEqualTo("021-****-4567");
    }

    @Test
    @DisplayName("mask: 숫자 8자리 미만은 형식 판단 불가 → 전부 마스킹, null·빈 값은 null")
    void mask_shortOrEmpty() {
        assertThat(PhoneMasker.mask("1234567")).isEqualTo("***-****-****");
        assertThat(PhoneMasker.mask(null)).isNull();
        assertThat(PhoneMasker.mask("  ")).isNull();
    }
}
