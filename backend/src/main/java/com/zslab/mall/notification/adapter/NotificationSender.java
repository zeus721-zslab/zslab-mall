package com.zslab.mall.notification.adapter;

import com.zslab.mall.notification.entity.NotificationLog;

/**
 * 알림 발송 추상화(Track 19·D-86 §후속 종결). 실 발송 어댑터 교체 지점이며 본 트랙은 {@link MockNotificationSender}로 구현한다.
 *
 * <p>{@link com.zslab.mall.payment.gateway.PaymentGateway} 패턴과 1:1 정합한다. 실 어댑터(SMTP·SMS·PUSH) 도입 시
 * 본 구현만 교체하고 계약은 유지한다. 발송 성공은 정상 반환으로, 실패는 {@link RuntimeException} 전파로 표현한다.
 * 발송 결과에 따른 상태 전이(markSent/markFailed)·실패 계측은 호출부({@code NotificationService.dispatch}) 책임이다.
 *
 * <p><b>예외 정책(Track 19 결정)</b>: 단순 {@link RuntimeException} 계약을 채택한다({@link MockNotificationSender}는
 * 예외를 던지지 않아 사용처가 없음). 채널별 예외 계층(커스텀 {@code NotificationDispatchException})은 실 어댑터
 * 진입(Track 20+) 시 도입을 검토한다.
 */
public interface NotificationSender {

    /**
     * 알림을 실제 채널로 발송한다. 본 트랙 {@link MockNotificationSender}는 외부 호출 없이 성공을 모사한다.
     *
     * @param notificationLog 발송 대상 알림 로그(channel·templateCode·recipient·target 식별 정보 보유)
     * @throws RuntimeException 발송 실패 시(실 어댑터 도입 시 채널별 예외 계층 도입 검토)
     */
    void send(NotificationLog notificationLog);
}
