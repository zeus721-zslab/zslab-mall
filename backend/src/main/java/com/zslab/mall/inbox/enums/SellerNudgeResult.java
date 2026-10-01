package com.zslab.mall.inbox.enums;

/**
 * 셀러 지연 독촉의 셀러별 결과(D-252). 응답에만 쓰이고 저장하지 않는다.
 */
public enum SellerNudgeResult {
    /** 발송 성공. */
    SENT,
    /** 발송 시도 실패(notification_log FAILED). */
    FAILED,
    /** 수신처 없음(contact_phone·활성 OWNER 연락처 모두 없음 · 로그 행 없음). */
    NO_RECIPIENT,
    /** 24시간 안에 같은 독촉을 이미 보냈다. */
    COOLDOWN,
    /** 발송 시점 재계산으로 지연 0건(또는 없는 셀러). */
    NO_DELAY
}
