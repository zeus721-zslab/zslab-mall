package com.zslab.mall.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Track 16 D-100 Q4 β′·종료조건 #6 검증 단위 테스트.
 *
 * <p><b>스코프</b>: zslab.event.published·zslab.event.failed 카운터 발화·event 태그 부착을 실측한다. MeterRegistry
 * mock 미사용 (SimpleMeterRegistry 실 카운터·Spring 컨텍스트 미기동).
 */
class EventMetricsRecorderTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EventMetricsRecorder recorder = new EventMetricsRecorder(registry);

    @Test
    @DisplayName("케이스1 recordPublished 호출 시 zslab.event.published{event} 카운터 1 증가")
    void records_published_counter() {
        recorder.recordPublished("OrderPlaced");

        assertThat(registry.find("zslab.event.published").tag("event", "OrderPlaced").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("케이스2 recordFailed 호출 시 zslab.event.failed{event} 카운터 1 증가")
    void records_failed_counter() {
        recorder.recordFailed("ClaimApproved");

        assertThat(registry.find("zslab.event.failed").tag("event", "ClaimApproved").counter().count())
                .isEqualTo(1.0);
    }
}
