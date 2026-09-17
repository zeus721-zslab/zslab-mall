package com.zslab.mall.claim.event;

import java.time.LocalDateTime;

/**
 * 반품 검수 합격 도메인 이벤트(Track 81-A D-170·R5). {@code ClaimService.inspect}가 PASS 저장 직후 발행한다(D-29 save→publish).
 * 소비처 {@code ClaimInspectionPassedHandler}(refund·AFTER_COMMIT)가 환불을 개시한다 — 수거 확인 시점 환불(구 ClaimPickedUpHandler)을
 * 검수 합격 시점으로 옮긴 트리거다.
 */
public record ClaimInspectionPassed(
        Long claimId,
        String claimPublicId,
        Long orderItemId,
        boolean restock,
        LocalDateTime occurredAt) {
}
