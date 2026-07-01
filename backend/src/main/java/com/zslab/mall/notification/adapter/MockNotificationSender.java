package com.zslab.mall.notification.adapter;

import com.zslab.mall.notification.entity.NotificationLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mock 발송 어댑터 구현(Track 19·D-86 §후속). 실제 외부 채널 호출 없이 발송을 모사하고 성공으로 반환한다.
 *
 * <p>{@link com.zslab.mall.payment.gateway.MockPaymentGateway} 패턴과 정합한다. 실 발송 어댑터(SMTP·SMS·PUSH) 도입 시
 * 본 구현만 교체하고 {@link NotificationSender} 계약은 유지한다. 외부 호출·예외 발생 없음.
 */
@Slf4j
@Component
public class MockNotificationSender implements NotificationSender {

    @Override
    public void send(NotificationLog notificationLog) {
        // Mock: 외부 채널 호출 대신 발송 사실만 로깅하고 성공 반환한다(실 어댑터 진입 시 본 구현만 교체).
        log.info("[MockNotificationSender] 발송 모사: channel={} template={} target_type={} target_id={} recipient={}",
                notificationLog.getChannel(), notificationLog.getTemplateCode(), notificationLog.getTargetType(),
                notificationLog.getTargetId(), notificationLog.getRecipientUserId());
    }
}
