package com.zslab.mall.common.serialization;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link KstOffsetSerializer}·{@link KstOffsetDeserializer} 검증(Track 69). 저장값이 KST 벽시계이므로
 * 직렬화는 {@code +09:00} 라벨만 부착하고(시각 무shift), 역직렬화는 오프셋을 제거해 원본 벽시계를 복원한다.
 *
 * <p>round-trip 대칭은 체크아웃 멱등 재요청이 캐시 응답 JSON을 되읽는 경로의 라이브트랩(직렬화/역직렬화 비대칭
 * 500)을 회귀 방어한다. 프로덕션과 동일한 필드 애노테이션 경로를 검증하려 holder 레코드를 사용한다.
 */
class KstOffsetSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** CheckoutResponse.expiresAt와 동일한 애노테이션 배선을 재현한 테스트 홀더. */
    private record Holder(
            @JsonSerialize(using = KstOffsetSerializer.class)
            @JsonDeserialize(using = KstOffsetDeserializer.class)
            LocalDateTime timestamp) {
    }

    @Test
    @DisplayName("직렬화: KST 벽시계가 시각 shift 없이 +09:00 라벨로 직렬화된다")
    void serialize_appendsKstOffsetWithoutShift() throws Exception {
        Holder holder = new Holder(LocalDateTime.of(2026, 7, 10, 16, 36, 35, 481_000_000));

        String json = objectMapper.writeValueAsString(holder);

        assertThat(json).isEqualTo("{\"timestamp\":\"2026-07-10T16:36:35.481+09:00\"}");
    }

    @Test
    @DisplayName("역직렬화: +09:00 문자열이 오프셋 제거된 원본 벽시계 LocalDateTime으로 복원된다")
    void deserialize_stripsOffsetToWallClock() throws Exception {
        String json = "{\"timestamp\":\"2026-07-10T16:36:35.481+09:00\"}";

        Holder holder = objectMapper.readValue(json, Holder.class);

        assertThat(holder.timestamp()).isEqualTo(LocalDateTime.of(2026, 7, 10, 16, 36, 35, 481_000_000));
    }

    @Test
    @DisplayName("round-trip 대칭: serialize→deserialize 왕복이 원본과 equal(멱등 body 정합)")
    void roundTrip_isSymmetric() throws Exception {
        LocalDateTime original = LocalDateTime.of(2026, 7, 10, 16, 36, 35, 481_000_000);

        String json = objectMapper.writeValueAsString(new Holder(original));
        Holder restored = objectMapper.readValue(json, Holder.class);

        assertThat(restored.timestamp()).isEqualTo(original);
    }
}
