package com.zslab.mall.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DiffBuilder} 검증 — 변경 필드만 추출·JSON 형태({"field":{"before":..,"after":..}})·변경 없음 규약.
 * ObjectMapper는 실 빈 동작을 검증하려 실제 인스턴스를 사용한다(직렬화 결과가 계약이므로 mock 부적합).
 */
class DiffBuilderTest {

    private final DiffBuilder diffBuilder = new DiffBuilder(new ObjectMapper());

    @Test
    @DisplayName("변경 필드만 추출: 값이 다른 key만 before/after로 담김")
    void diff_changedFieldsOnly() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", "PENDING");
        before.put("name", "동일");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "CONFIRMED");
        after.put("name", "동일");

        Map<String, Object> diff = diffBuilder.diff(before, after);

        assertThat(diff).containsOnlyKeys("status");
        assertThat(diff.get("status")).isEqualTo(Map.of("before", "PENDING", "after", "CONFIRMED"));
    }

    @Test
    @DisplayName("추가·삭제 key: 한쪽에만 있으면 null 대응값으로 변경 포함")
    void diff_addedAndRemovedKeys() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("removed", "old");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("added", "new");

        Map<String, Object> diff = diffBuilder.diff(before, after);

        assertThat(diff).containsOnlyKeys("removed", "added");
        assertThat(diff.get("removed")).isEqualTo(mapWithNull("before", "old", "after"));
        assertThat(diff.get("added")).isEqualTo(mapWithNull("after", "new", "before"));
    }

    @Test
    @DisplayName("변경 없음: 모든 필드 동일 → 빈 맵·toJson은 \"{}\"")
    void diff_noChange_emptyMap() {
        Map<String, Object> same = Map.of("status", "SALE");

        Map<String, Object> diff = diffBuilder.diff(same, same);

        assertThat(diff).isEmpty();
        assertThat(diffBuilder.toJson(diff)).isEqualTo("{}");
    }

    @Test
    @DisplayName("toJson: 변경 diff를 {\"field\":{\"before\":..,\"after\":..}} JSON 문자열로 직렬화")
    void toJson_serializesChangeShape() {
        Map<String, Object> before = Map.of("status", "PENDING");
        Map<String, Object> after = Map.of("status", "SALE");

        String json = diffBuilder.toJson(diffBuilder.diff(before, after));

        assertThat(json).isEqualTo("{\"status\":{\"before\":\"PENDING\",\"after\":\"SALE\"}}");
    }

    @Test
    @DisplayName("null 입력: before/after null은 빈 맵 취급 → 빈 diff")
    void diff_nullInputs_treatedAsEmpty() {
        assertThat(diffBuilder.diff(null, null)).isEmpty();
    }

    /** 특정 key만 null 값을 갖는 before/after 쌍 맵 구성(Map.of는 null 불가라 헬퍼로 만든다). */
    private Map<String, Object> mapWithNull(String presentKey, Object presentValue, String nullKey) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(nullKey, null);
        map.put(presentKey, presentValue);
        return map;
    }
}
