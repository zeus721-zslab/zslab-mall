package com.zslab.mall.common.observability;

import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Track 16 D-100 Q3 β′·Q12 β″′·종료조건 #5 박제. ApplicationEventPublisher를 래핑해 이벤트 발행 시점에 eventName MDC를
 * 단일 SoT로 주입한다. publishEvent 호출 후 finally로 MDC.remove("eventName") 정리.
 *
 * <p><b>동일 스레드·자연 상속(D-100 Q14 ✓)</b>: AFTER_COMMIT 핸들러는 발행처와 동일 스레드에서 동기 실행되며 MDC는
 * thread-local로 자연 상속된다(MdcPropagationTest 실측 완료). eventName MDC.put 시점은 publishEvent 호출 전·핸들러
 * 진입까지 보존된다.
 *
 * <p><b>트랜잭션 경계 무관(D-29 save→publish 정합)</b>: 본 wrapper는 트랜잭션을 시작·종료하지 않으며 호출 측 트랜잭션
 * 경계를 그대로 따른다. delegate.publishEvent 시그니처는 ApplicationEventPublisher와 동일하므로 5 Service 호출 라인
 * 무변경(생성자 주입 의존만 교체).
 *
 * <p><b>스코프(Q3 β′ 박제)</b>: traceId·correlationId 주입은 TraceIdFilter(D-48 SoT) 책임. eventName 주입은 본 클래스
 * 책임. 두 책임은 분리되며 wrapper는 HTTP 요청 컨텍스트와 무관(요청 외 기원 발행도 자연 적용).
 *
 * <p><b>Outbox 도입 트리거(D-100 Q2 γ)</b>: 본 wrapper는 Outbox writer 자연 흡수 진입점. 트리거 4건 중 1건 도달 시
 * publishEvent 내부에 outbox 적재 로직 추가로 자연 확장.
 */
@Component
@RequiredArgsConstructor
public class TracedEventPublisher {

    public static final String EVENT_NAME = "eventName";

    private final ApplicationEventPublisher delegate;

    public void publishEvent(Object event) {
        MDC.put(EVENT_NAME, event.getClass().getSimpleName());
        try {
            delegate.publishEvent(event);
        } finally {
            MDC.remove(EVENT_NAME);
        }
    }
}
