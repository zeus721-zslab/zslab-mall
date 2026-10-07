package com.zslab.mall.notification.adapter;

import com.zslab.mall.notification.entity.NotificationLog;

/**
 * 이메일 발송 추상화(Track 19·D-86 §후속 종결·D-209 선택 장치). 구현체는 {@link MockNotificationSender}(기본)와
 * {@link SmtpNotificationSender}({@code zslab.notification.email-sender=smtp})다.
 *
 * <p>수신 주소는 어댑터가 {@code notificationLog.getRecipientUserId()}로 조회하고, 제목은 {@code notificationLog.getTitle()}이다.
 * <b>본문은 별도 인자로 받는다(D-269)</b> — 민감 메일은 {@code notification_log.content}에 마스킹본만 저장하므로 로그 행의 content를
 * 그대로 보내면 마스킹본이 발송된다. 발송 성공은 정상 반환, 실패는 {@link RuntimeException} 전파로 표현하며
 * 상태 전이(markSent/markFailed)·실패 계측은 호출부({@code NotificationService.sendEmail}) 책임이다.
 */
public interface NotificationSender {

    /**
     * 알림 메일을 발송한다.
     *
     * @param notificationLog 발송 대상 알림 로그(수신 회원·제목·템플릿·target 식별 정보 보유)
     * @param body            발송 본문 원문(민감 메일이면 저장본과 다르다·로그 금지)
     * @throws RuntimeException 발송 실패 시(수신 주소 없음 포함)
     */
    void send(NotificationLog notificationLog, String body);
}
