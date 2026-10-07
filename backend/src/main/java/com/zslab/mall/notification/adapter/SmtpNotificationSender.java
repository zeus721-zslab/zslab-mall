package com.zslab.mall.notification.adapter;

import com.zslab.mall.notification.entity.NotificationLog;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * SMTP 실발송 어댑터(D-269). {@code zslab.notification.email-sender=smtp}일 때만 등록되고, 그때 {@link MockNotificationSender}는 빠진다
 * (D-209 선택 장치 — 조용한 Mock 폴백 없음). 수신 주소는 D-209 결정 7대로 어댑터가 {@code recipientUserId}로 {@code User.email}을 조회한다.
 *
 * <p>접속 정보는 {@code spring.mail.*}(.env {@code SMTP_*})이고 발신 주소는 {@code spring.mail.username}이다. smtp를 골랐는데 호스트·발신 주소가
 * 비어 있으면 첫 발송이 아니라 기동에서 실패시킨다(설정 누락을 배포 시점에 드러낸다). 본문·수신 주소는 로그에 남기지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zslab.notification.email-sender", havingValue = "smtp")
public class SmtpNotificationSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final UserRepository userRepository;
    private final String fromAddress;

    /**
     * @throws IllegalStateException SMTP 호스트 또는 발신 주소(SMTP_USERNAME)가 비어 있는 경우(기동 실패)
     */
    public SmtpNotificationSender(
            JavaMailSender mailSender,
            UserRepository userRepository,
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.username:}") String fromAddress) {
        if (host.isBlank() || fromAddress.isBlank()) {
            throw new IllegalStateException("EMAIL_SENDER=smtp에는 SMTP_HOST와 SMTP_USERNAME(발신 주소)이 필요합니다.");
        }
        this.mailSender = mailSender;
        this.userRepository = userRepository;
        this.fromAddress = fromAddress;
    }

    /**
     * @throws IllegalStateException 수신 회원이 없거나 이메일이 비어 있는 경우(탈퇴 비식별화·셀러 SMS 경로처럼 회원 없는 로그) — 호출부가 FAILED로 전이
     */
    @Override
    public void send(NotificationLog notificationLog, String body) {
        String recipientEmail = resolveRecipientEmail(notificationLog.getRecipientUserId());
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipientEmail);
        message.setSubject(notificationLog.getTitle());
        message.setText(body);
        mailSender.send(message);
        log.info("[SmtpNotificationSender] 발송: template={} target_type={} target_id={} recipient={}",
                notificationLog.getTemplateCode(), notificationLog.getTargetType(), notificationLog.getTargetId(),
                notificationLog.getRecipientUserId());
    }

    private String resolveRecipientEmail(Long recipientUserId) {
        if (recipientUserId == null) {
            throw new IllegalStateException("수신 회원이 없는 메일은 보낼 수 없습니다.");
        }
        String email = userRepository.findById(recipientUserId).map(User::getEmail).orElse(null);
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("수신 회원의 이메일이 없습니다: userId=" + recipientUserId);
        }
        return email;
    }
}
