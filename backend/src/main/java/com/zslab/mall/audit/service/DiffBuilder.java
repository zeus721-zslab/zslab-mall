package com.zslab.mall.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * before/after 필드맵에서 변경된 필드만 추린 diff 맵과 그 JSON 직렬화를 책임진다(Track 52 Phase 1·결정3 역할 분리).
 *
 * <p><b>AUD-3(changed_fields 한정)</b>: {@link #diff(Map, Map)}는 {@code before}≠{@code after}인 key만
 * {@code {"field":{"before":..,"after":..}}} 형태로 담는다. 값 비교는 {@link Objects#equals}이며 순서는 삽입 순서를 보존한다.
 *
 * <p><b>빈-diff 규약</b>: 변경이 하나도 없으면 {@link #diff(Map, Map)}는 <b>빈 맵</b>(isEmpty)을 반환하고,
 * {@link #toJson(Map)}은 그 빈 맵을 {@code "{}"}로 직렬화한다. 적재 skip 여부는 {@link AuditRecorder}가 판단한다(본 클래스는 무판단).
 * {@code null} 입력은 빈 맵으로 간주한다.
 *
 * <p>JSON 직렬화는 Spring Boot 기본 {@link ObjectMapper} 빈을 주입해 사용한다(신규 의존성·커스텀 매퍼 미도입).
 */
@Component
@RequiredArgsConstructor
public class DiffBuilder {

    private final ObjectMapper objectMapper;

    /**
     * 두 필드맵의 변경분만 담은 diff 맵을 만든다. 반환 맵의 각 엔트리 = {@code field -> {"before":..,"after":..}}.
     * 변경이 없으면 빈 맵을 반환한다.
     */
    public Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> beforeMap = (before == null) ? Map.of() : before;
        Map<String, Object> afterMap = (after == null) ? Map.of() : after;

        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(beforeMap.keySet());
        keys.addAll(afterMap.keySet());

        Map<String, Object> changed = new LinkedHashMap<>();
        for (String key : keys) {
            Object beforeValue = beforeMap.get(key);
            Object afterValue = afterMap.get(key);
            if (!Objects.equals(beforeValue, afterValue)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("before", beforeValue);
                change.put("after", afterValue);
                changed.put(key, change);
            }
        }
        return changed;
    }

    /**
     * diff 맵을 diff_json 문자열로 직렬화한다. 빈 맵은 {@code "{}"}가 된다.
     *
     * @throws IllegalStateException 직렬화에 실패한 경우(감사 무결성 우선·묵살 금지)
     */
    public String toJson(Map<String, Object> diff) {
        try {
            return objectMapper.writeValueAsString(diff == null ? Map.of() : diff);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("diff_json 직렬화에 실패했습니다.", exception);
        }
    }
}
