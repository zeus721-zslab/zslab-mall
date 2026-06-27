package com.zslab.mall.checkout.enums;

/**
 * 멱등성 처리 상태(내부 기술 상태·§12·UI 비노출). DDL {@code order_idempotency_key.status} ENUM 정합(D-44a·D-52).
 *
 * <ul>
 *   <li>IN_PROGRESS: 키 선점·처리 진행 중(order_id 미할당이면 409 차단·할당이면 복구 대상)</li>
 *   <li>COMPLETED: 2xx 성공 응답 캐싱 완료(재요청 시 캐시 반환·HTTP 200)</li>
 * </ul>
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED
}
