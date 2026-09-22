package com.zslab.mall.notification.adapter;

import com.zslab.mall.common.util.PhoneMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock SMS 발송 어댑터(Track 80 D-169). 실제 업체 호출 없이 발송 사실만 로그로 남기고 성공 반환한다.
 *
 * <p>수신번호는 {@link PhoneMasker}로 가려서 기록한다(로그에 개인정보 원문 금지). 실 업체 어댑터 도입 시 본 구현만 교체한다.
 * 활성 조건은 {@code zslab.notification.sms-sender=mock}(미지정 시 mock·Track 97 D-209)이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zslab.notification.sms-sender", havingValue = "mock", matchIfMissing = true)
public class MockSmsSender implements SmsSender {

    @Override
    public void send(String phoneNumber, String content) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("SMS 수신번호는 필수입니다.");
        }
        // Mock: 외부 호출 대신 발송 사실만 로깅하고 성공 반환한다(실 어댑터 진입 시 본 구현만 교체).
        // 본문은 임시 비밀번호 등 민감 정보를 담을 수 있어 길이만 남긴다(Track 84).
        log.info("[MockSmsSender] 발송 모사: to={} contentLength={}", PhoneMasker.mask(phoneNumber), content == null ? 0 : content.length());
    }
}
