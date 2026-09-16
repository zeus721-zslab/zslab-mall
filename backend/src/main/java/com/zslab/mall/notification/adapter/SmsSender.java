package com.zslab.mall.notification.adapter;

/**
 * SMS 발송 추상화(Track 80 D-169·공용). 수신번호·본문만 받으며 주문·클레임 등 도메인 타입에 의존하지 않는다 — 어느 도메인이든
 * {@code smsSender.send(phoneNumber, content)} 한 줄로 발송할 수 있다.
 *
 * <p>{@link NotificationSender}(NotificationLog 기반·EMAIL 모사)와 나란히 두는 채널 전용 계약이다. 실 SMS 업체 어댑터 도입 시
 * {@link MockSmsSender}만 교체하고 본 계약은 유지한다. 발송 성공은 정상 반환, 실패는 {@link RuntimeException} 전파로 표현하며
 * 로그 상태 전이(SENT/FAILED)는 호출부({@code NotificationService}) 책임이다.
 */
public interface SmsSender {

    /**
     * SMS를 발송한다.
     *
     * @param phoneNumber 수신 번호(숫자·하이픈 허용)
     * @param content     본문
     * @throws RuntimeException 발송 실패 시
     */
    void send(String phoneNumber, String content);
}
