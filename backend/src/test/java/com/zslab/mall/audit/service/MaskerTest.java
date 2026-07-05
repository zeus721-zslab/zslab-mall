package com.zslab.mall.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Masker} 검증 — 민감 필드 마스킹 훅 동작(대상 있으면 마스킹·없으면 통과)·원본 불변.
 */
class MaskerTest {

    private final Masker masker = new Masker();

    @Test
    @DisplayName("현 3소비처 필드(status·gradeId 등): 민감 아님 → 값 그대로 통과")
    void mask_nonSensitive_passThrough() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", Map.of("before", "PENDING", "after", "SALE"));
        fields.put("gradeId", Map.of("before", 1, "after", 2));

        Map<String, Object> masked = masker.mask(fields);

        assertThat(masked).isEqualTo(fields);
    }

    @Test
    @DisplayName("민감 필드(passwordHash·accountNumber): 값 전체를 MASK로 치환")
    void mask_sensitive_replacedWithMask() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("passwordHash", Map.of("before", "old-hash", "after", "new-hash"));
        fields.put("accountNumber", Map.of("before", "110-1", "after", "220-2"));
        fields.put("status", Map.of("before", "A", "after", "B"));

        Map<String, Object> masked = masker.mask(fields);

        assertThat(masked.get("passwordHash")).isEqualTo(Masker.MASK);
        assertThat(masked.get("accountNumber")).isEqualTo(Masker.MASK);
        assertThat(masked.get("status")).isEqualTo(Map.of("before", "A", "after", "B"));
    }

    @Test
    @DisplayName("원본 불변: mask는 새 맵을 반환하고 입력을 수정하지 않음")
    void mask_doesNotMutateInput() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("password", "secret");

        masker.mask(fields);

        assertThat(fields.get("password")).isEqualTo("secret");
    }

    @Test
    @DisplayName("null·빈 맵: 빈 맵 반환")
    void mask_nullOrEmpty_returnsEmpty() {
        assertThat(masker.mask(null)).isEmpty();
        assertThat(masker.mask(Map.of())).isEmpty();
    }
}
