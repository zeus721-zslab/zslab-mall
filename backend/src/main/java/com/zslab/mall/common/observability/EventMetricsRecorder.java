package com.zslab.mall.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Track 16 D-100 Q4 β′·종료조건 #6 박제·β 채택 (별도 클래스 SoT·SRP 정합·TracedEventPublisher 책임 최소화 옵션 4 정합).
 *
 * <p><b>발화 위치</b>: TracedEventPublisher.publishEvent에서 recordPublished 호출·Notification 7 핸들러 catch 블록에서
 * recordFailed 호출. handler 태그 미부착 (Q4 β′·rename 시계열 보존·저카디널리티).
 *
 * <p><b>Outbox 도입 시 SoT 유지</b>: 본 클래스는 Outbox writer 진입 후에도 published 카운터 단일 SoT 역할 유지·
 * Outbox writer 내부에서 recordPublished 호출 자연 정합.
 */
@Component
@RequiredArgsConstructor
public class EventMetricsRecorder {

    private final MeterRegistry registry;

    public void recordPublished(String eventName) {
        Counter.builder("zslab.event.published")
                .tag("event", eventName)
                .register(registry)
                .increment();
    }

    public void recordFailed(String eventName) {
        Counter.builder("zslab.event.failed")
                .tag("event", eventName)
                .register(registry)
                .increment();
    }
}
