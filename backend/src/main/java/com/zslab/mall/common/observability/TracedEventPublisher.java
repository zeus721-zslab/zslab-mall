package com.zslab.mall.common.observability;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Track 16 D-100 Q3 β′ 옵션 4·Q12 β″′·종료조건 #5 박제. ApplicationEventPublisher를 래핑한 단순 위임자.
 * 11 발행처 단일 진입점으로 작동하며, Outbox 도입 시 본 클래스가 자연 흡수 진입점이 된다(Q2 γ 트리거 정합).
 *
 * <p><b>책임 재정의(옵션 4·2026-06-30·실측 기반)</b>: 초기 박제는 eventName MDC 주입을 본 클래스 책임으로
 * 가정했으나, AFTER_COMMIT 핸들러 발화 시점과 publishEvent 동기 구간 종료 시점 미스매치로 핸들러 진입 시
 * MDC eventName 부재 실측 확정(CorrelationIdIntegrationTest). nested publishEvent 패턴 MDC eventName 오염
 * 위험 동반 → 본 클래스는 eventName MDC 주입 책임 없음. 운영 로그 eventName 출력은 핸들러 catch 6 표준키
 * {@code event.getClass().getSimpleName()} 직접 인용으로 충족(D-100 Q6 β 정합).
 *
 * <p><b>트랜잭션 경계 무관(D-29 save→publish 정합)</b>: 본 wrapper는 트랜잭션을 시작·종료하지 않으며 호출 측
 * 트랜잭션 경계를 그대로 따른다. delegate.publishEvent 시그니처는 ApplicationEventPublisher와 동일·5 Service
 * 호출 라인 무변경(생성자 주입 의존만 교체).
 *
 * <p><b>스코프</b>: traceId·correlationId 주입은 TraceIdFilter(D-48 SoT) 책임. 본 클래스는 발행 경로 단일
 * SoT 역할만 수행하며 향후 Outbox writer·메트릭 카운터(PR-2 zslab.event.published)·이벤트 저장소 등의 자연
 * 흡수 진입점 보존.
 */
@Component
@RequiredArgsConstructor
public class TracedEventPublisher {

    private final ApplicationEventPublisher delegate;

    public void publishEvent(Object event) {
        delegate.publishEvent(event);
    }
}
