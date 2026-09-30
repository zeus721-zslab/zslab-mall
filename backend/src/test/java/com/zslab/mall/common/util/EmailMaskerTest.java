package com.zslab.mall.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EmailMasker 단위 테스트. 기대값은 추출 전 정산 셀러 연락처 마스킹(AdminSettlementQueryService.maskEmail)과 같은 결과다 — 로컬파트 앞 2자만 노출,
 * 2자 이하면 로컬파트 전부 가림, '@' 없음·맨 앞이면 전부 가림, null·공백은 null.
 */
class EmailMaskerTest {

    @Test
    @DisplayName("로컬파트 3자 이상 → 앞 2자 + *** + @도메인")
    void masksLocalPartAfterTwoCharacters() {
        assertThat(EmailMasker.mask("abcdef@example.com")).isEqualTo("ab***@example.com");
        assertThat(EmailMasker.mask("abc@example.com")).isEqualTo("ab***@example.com");
    }

    @Test
    @DisplayName("로컬파트 2자 이하 → 로컬파트 전부 가림")
    void masksWholeShortLocalPart() {
        assertThat(EmailMasker.mask("ab@example.com")).isEqualTo("***@example.com");
        assertThat(EmailMasker.mask("a@example.com")).isEqualTo("***@example.com");
    }

    @Test
    @DisplayName("'@' 없음·맨 앞 → 전부 가림 / null·공백 → null")
    void masksMalformedAndKeepsMissing() {
        assertThat(EmailMasker.mask("no-at-sign")).isEqualTo("***");
        assertThat(EmailMasker.mask("@example.com")).isEqualTo("***");
        assertThat(EmailMasker.mask(null)).isNull();
        assertThat(EmailMasker.mask("  ")).isNull();
    }
}
