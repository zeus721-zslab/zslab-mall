package com.zslab.mall.notification.template;

/**
 * 채널 발송 본문 템플릿 상수(Track 97 D-209). 서비스 2곳({@code AdminMemberCommandService}·{@code AdminMemberProvisioningService})에
 * 같은 문구가 중복돼 있던 것을 한 곳으로 모은다. 문구 변경 시 여기만 고친다.
 */
public final class NotificationMessages {

    /** 임시 비밀번호 SMS 본문. {@code %s}에 평문이 들어가며 notification_log 저장본은 호출자가 마스킹 문자열로 치환한다. */
    public static final String TEMPORARY_PASSWORD_SMS = "[zslab-mall] 임시 비밀번호: %s 로그인 후 비밀번호를 변경해 주세요.";

    private NotificationMessages() {
    }
}
