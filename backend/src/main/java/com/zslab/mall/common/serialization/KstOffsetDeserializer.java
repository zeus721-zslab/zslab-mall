package com.zslab.mall.common.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * {@link KstOffsetSerializer}가 내보낸 {@code +09:00} 오프셋 문자열을 {@link LocalDateTime}으로 되돌린다(Track 69).
 *
 * <p>체크아웃 멱등 재요청 경로가 캐시된 응답 JSON을 {@code CheckoutResponse}로 역직렬화할 때(round-trip),
 * 기본 LocalDateTime 역직렬화기는 오프셋을 파싱하지 못한다. 직렬화기와 대칭을 이루도록 오프셋을 파싱한 뒤
 * {@code toLocalDateTime()}으로 KST 벽시계를 그대로 복원한다(오프셋 제거·시각 무변환).
 */
public class KstOffsetDeserializer extends JsonDeserializer<LocalDateTime> {

    @Override
    public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return OffsetDateTime.parse(parser.getValueAsString()).toLocalDateTime();
    }
}
