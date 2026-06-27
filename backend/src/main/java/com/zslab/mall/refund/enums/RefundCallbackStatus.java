package com.zslab.mall.refund.enums;

/**
 * PG 환불 webhook 콜백 결과(expected-spec §6.2). 콜백 페이로드 {@code status} 필드의 값 집합이다.
 *
 * <p>Track 3 {@code CallbackType}(SUCCESS·FAILURE·CANCEL) 패턴을 미러한 webhook 전용 enum이며, DB 컬럼이 아니다.
 * 매직 문자열 방지를 위해 enum으로 고정한다.
 */
public enum RefundCallbackStatus {
    /** PG 환불 성공 통지 → Refund.PENDING → COMPLETED. */
    SUCCESS,
    /** PG 환불 실패 통지 → Refund.PENDING → FAILED. */
    FAIL
}
