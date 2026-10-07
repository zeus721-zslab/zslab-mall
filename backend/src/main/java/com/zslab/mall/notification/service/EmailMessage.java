package com.zslab.mall.notification.service;

import com.zslab.mall.common.enums.PolymorphicTargetType;
import java.util.Map;

/**
 * 범용 메일 발송 요청(D-269 · {@link NotificationService#sendEmail}). 본문은 {@code {{이름}}} 자리표시자를 가진 템플릿과 변수로 받는다.
 *
 * <p>{@code sensitive=true}면 발송은 변수를 채운 원문으로 하고 {@code notification_log.content}에는 모든 변수를 마스킹 문자열로 채운 본문을
 * 저장한다(재설정 링크 등 평문 잔존 금지). false면 원문 그대로 저장한다(기존 알림 메일 — 변수 없이 완성된 본문을 템플릿으로 넘긴다).
 *
 * @param recipientUserId 수신 회원 id(주소는 발송 어댑터가 조회)
 * @param subject         제목(로그 title)
 * @param bodyTemplate    본문 템플릿({@code {{이름}}} 자리표시자 · 변수에 없는 자리표시자는 그대로 남는다)
 * @param variables       자리표시자 값(없으면 빈 맵)
 * @param sensitive       저장본 마스킹 여부
 * @param templateCode    템플릿 코드({@code NotificationTemplateCodes})
 * @param targetType      대상 유형
 * @param targetId        대상 id
 * @param eventName       실패 계측 태그({@code zslab.notification.failed{event}})
 */
public record EmailMessage(
        Long recipientUserId,
        String subject,
        String bodyTemplate,
        Map<String, String> variables,
        boolean sensitive,
        String templateCode,
        PolymorphicTargetType targetType,
        Long targetId,
        String eventName) {

    /** 저장본에서 변수 자리에 들어가는 문자열(임시 비밀번호 SMS 마스킹과 같은 표기). */
    static final String MASK = "****";
    private static final String PLACEHOLDER_OPEN = "{{";
    private static final String PLACEHOLDER_CLOSE = "}}";

    public EmailMessage {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    /** 발송 원문(변수 채움). */
    String renderBody() {
        return render(false);
    }

    /** notification_log.content 저장본 — 민감 메일은 모든 변수를 마스킹한다. */
    String renderStoredContent() {
        return render(sensitive);
    }

    /** 변수 값이 들어간 원문은 toString에 싣지 않는다(로그·예외 메시지로 토큰이 새지 않게). */
    @Override
    public String toString() {
        return "EmailMessage[recipientUserId=" + recipientUserId + ", templateCode=" + templateCode + ", sensitive=" + sensitive + "]";
    }

    private String render(boolean masked) {
        String rendered = bodyTemplate;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            String value = masked ? MASK : variable.getValue();
            rendered = rendered.replace(PLACEHOLDER_OPEN + variable.getKey() + PLACEHOLDER_CLOSE, value);
        }
        return rendered;
    }
}
