package com.zslab.mall.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Track 16 D-100 Q3 β′ 옵션 4·Q12 β″′·종료조건 #5 검증 단위 테스트.
 *
 * <p><b>의도(재설계·2026-06-30)</b>: TracedEventPublisher가 옵션 4 정합으로 단순 delegate 위임자 역할만 수행하며
 * MDC 책임을 보유하지 않음을 실측한다. 초기 명세의 eventName MDC 주입·정리 검증은 옵션 4 채택 박제 정합으로 폐기.
 *
 * <p><b>스코프</b>: delegate 위임 검증·MDC 미오염 검증. 5 Service 발행처 교체 회귀는 통합 테스트 20건이 검증.
 */
class TracedEventPublisherTest {

    private final ApplicationEventPublisher delegate = mock(ApplicationEventPublisher.class);
    private final TracedEventPublisher publisher = new TracedEventPublisher(delegate);

    @BeforeEach
    void setUp() {
        // 테스트 격리: 직전 케이스·스레드 재사용 MDC 누수 차단(미오염 단언 신뢰성 확보).
        MDC.clear();
    }

    @Test
    @DisplayName("케이스1 publishEvent 호출 시 delegate 위임")
    void delegates_to_publisher() {
        TestEvent event = new TestEvent("p1");

        publisher.publishEvent(event);

        verify(delegate, times(1)).publishEvent(any(Object.class)); // publishEvent(Object) 오버로드 지정(record는 ApplicationEvent 아님)
        verify(delegate, times(1)).publishEvent(event);
    }

    @Test
    @DisplayName("케이스2 publishEvent 호출 시 MDC 미오염(옵션 4 책임 미보유)")
    void does_not_pollute_mdc() {
        publisher.publishEvent(new TestEvent("p1"));

        // 옵션 4: wrapper는 어떤 MDC 키도 주입하지 않는다(eventName은 핸들러 직접 인용·traceId/correlationId는 TraceIdFilter 책임).
        assertThat(MDC.get("eventName")).isNull();
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("correlationId")).isNull();
    }

    /** 발행 대상 더미 이벤트. */
    record TestEvent(String payload) {}
}
