package com.zslab.mall.notification.template;

/**
 * 채널 발송 본문 템플릿 상수(Track 97 D-209). 서비스 2곳({@code AdminMemberCommandService}·{@code AdminMemberProvisioningService})에
 * 같은 문구가 중복돼 있던 것을 한 곳으로 모은다. 문구 변경 시 여기만 고친다.
 */
public final class NotificationMessages {

    /** 임시 비밀번호 SMS 본문. {@code %s}에 평문이 들어가며 notification_log 저장본은 호출자가 마스킹 문자열로 치환한다. */
    public static final String TEMPORARY_PASSWORD_SMS = "[zslab-mall] 임시 비밀번호: %s 로그인 후 비밀번호를 변경해 주세요.";

    /** 셀러 지연 독촉 SMS 본문(D-252). {@code %d} 2개 = 기한이 지난 발송 대기·상품 Q&amp;A 미답변 건수. */
    public static final String SELLER_DELAY_NUDGE_SMS =
            "[zslab-mall] 처리 기한이 지난 건이 있습니다. 발송 대기 %d건, 상품 Q&A 미답변 %d건. 셀러센터 인박스에서 확인해 주세요.";

    /** 비밀번호 재설정 메일 제목(D-269). */
    public static final String PASSWORD_RESET_EMAIL_SUBJECT = "[zslab-mall] 비밀번호 재설정 안내";

    /**
     * 비밀번호 재설정 메일 본문(D-269). {@code %d} = 유효 시간(분·호출자가 formatted로 채움) · {@code {{resetLink}}} = EmailMessage 변수(저장본은 마스킹).
     */
    public static final String PASSWORD_RESET_EMAIL_BODY = """
            비밀번호 재설정을 요청하셨습니다.
            아래 링크에서 새 비밀번호를 설정해 주세요. 링크는 %d분 동안 한 번만 쓸 수 있습니다.

            {{resetLink}}

            요청하지 않으셨다면 이 메일을 무시하세요. 비밀번호는 바뀌지 않습니다.
            """;

    private NotificationMessages() {
    }
}
