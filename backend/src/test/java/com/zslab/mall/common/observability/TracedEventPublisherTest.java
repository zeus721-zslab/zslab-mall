package com.zslab.mall.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Track 16 D-100 Q3 β′·Q12 β″′·종료조건 #5 검증 단위 테스트.
 *
 * <p><b>의도</b>: TracedEventPublisher가 publishEvent 호출 시 eventName MDC 주입·delegate 위임·try-finally MDC 정리
 * 보장하는지 실측한다.
 *
 * <p><b>스코프</b>: wrapper 단독 책임·delegate 호출 라우팅·MDC put/remove 패턴 검증. 5 Service 발행처 교체 회귀는
 * 통합 테스트 20건이 검증.
 */
class TracedEventPublisherTest {

    private final ApplicationEventPublisher delegate = mock(ApplicationEventPublisher.class);
    private final TracedEventPublisher publisher = new TracedEventPublisher(delegate);

    @AfterEach
    void tearDown() {
        // 단위 테스트 스레드 재사용 시 누수 차단 (wrapper 정상 정리와 별개 안전망).
        MDC.clear();
    }

    @Test
    @DisplayName("케이스1 publishEvent 호출 시 eventName MDC 주입 + delegate 위임")
    void injects_eventName_and_delegates() {
        // delegate 호출 시점(요청 처리 중)의 MDC 스냅샷을 캡처한다. 호출 종료 후엔 finally로 정리되므로
        // verify 시점이 아닌 호출 시점에 캡처해야 한다.
        String[] capturedAtDelegate = new String[1];
        doAnswer(invocation -> {
            capturedAtDelegate[0] = MDC.get(TracedEventPublisher.EVENT_NAME);
            return null;
        }).when(delegate).publishEvent(any(Object.class)); // publishEvent(Object) 오버로드 지정(record는 ApplicationEvent 아님)

        TestEvent event = new TestEvent("p1");
        publisher.publishEvent(event);

        verify(delegate, times(1)).publishEvent(event);
        assertThat(capturedAtDelegate[0]).isEqualTo("TestEvent");
    }

    @Test
    @DisplayName("케이스2 publishEvent 완료 후 eventName MDC 정리")
    void clears_eventName_after_publish() {
        publisher.publishEvent(new TestEvent("p1"));

        assertThat(MDC.get(TracedEventPublisher.EVENT_NAME)).isNull();
    }

    @Test
    @DisplayName("케이스3 delegate 예외 발생 시에도 try-finally eventName MDC 정리 보장")
    void clears_eventName_even_when_delegate_throws() {
        doThrow(new RuntimeException("publish boom"))
                .when(delegate)
                .publishEvent(any(Object.class)); // publishEvent(Object) 오버로드 지정

        assertThatThrownBy(() -> publisher.publishEvent(new TestEvent("p1")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("publish boom");

        assertThat(MDC.get(TracedEventPublisher.EVENT_NAME)).isNull();
    }

    /** 발행 대상 더미 이벤트. getSimpleName()이 "TestEvent"로 MDC에 주입되는지만 검증한다. */
    record TestEvent(String payload) {}
}
