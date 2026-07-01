package com.zslab.mall.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 알림 발송 실패 계측 SoT(Track 19·판단 3 β·D-100 Q4 β′ 정합). 발송 축(dispatch failed) 전용 카운터를
 * 이벤트 축({@link EventMetricsRecorder}의 published·failed)과 별도 클래스로 분리해 관측성 책임을 나눈다(SRP·D-100 Q6).
 *
 * <p><b>발화 위치</b>: {@code NotificationService.dispatch} catch 블록에서 발송 실패 시 호출한다.
 * {@code zslab.notification.failed{event, channel}} 카운터 1종. event·channel은 저카디널리티
 * (channel 4값·event 발행 이벤트 수 한정)로 D-100 Q4 β′ 시계열 보존·태그 폭발 회피 원칙에 정합한다.
 * template_code는 중카디널리티라 태그로 부착하지 않는다.
 *
 * <p><b>Track 20+ 확장 안전 지대</b>: 실 발송 어댑터(SMTP·SMS·PUSH) 도입 후에도 본 클래스가 발송 실패 계측의
 * 단일 SoT 역할을 유지한다.
 */
@Component
@RequiredArgsConstructor
public class NotificationDispatchMetricsRecorder {

    private final MeterRegistry registry;

    public void recordFailed(String eventName, String channel) {
        Counter.builder("zslab.notification.failed")
                .tag("event", eventName)
                .tag("channel", channel)
                .register(registry)
                .increment();
    }
}
