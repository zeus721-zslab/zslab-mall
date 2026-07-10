package com.zslab.mall.common.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * {@link LocalDateTime} 응답 필드를 KST(+09:00) 오프셋 라벨을 부착한 ISO-8601 문자열로 직렬화한다(Track 69).
 *
 * <p>저장값은 이미 KST 벽시계(JVM TZ=Asia/Seoul·hibernate.jdbc.time_zone=Asia/Seoul)이므로
 * UTC 재해석 없이 {@code +09:00} 오프셋만 부착한다. 예: {@code 2026-07-10T16:36:35.481+09:00}.
 *
 * <p>전역 ObjectMapper 모듈로 등록하지 않고, 응답 노출 시각 필드에 {@code @JsonSerialize}로만 적용한다.
 */
public class KstOffsetSerializer extends JsonSerializer<LocalDateTime> {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @Override
    public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        gen.writeString(value.atOffset(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }
}
