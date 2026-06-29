package com.zslab.mall.claim.event;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import java.time.LocalDateTime;

/**
 * 클레임 종결 도메인 이벤트(D-30 사실 통지·QB-13 record 패턴·D-90 Q4). Spring ApplicationEvent로 발행한다.
 *
 * <p>발행 시점은 {@code ClaimService.markCompleted}의 save 직후다(D-29 save→publish·no flush). 소비측 핸들러
 * {@code ClaimCompletedHandler}(OrderItem CANCEL_REQUESTED → CANCELLED 종결 전이)는 Track 9 PR-C 소관이다.
 */
public record ClaimCompleted(
        Long claimId,
        String claimPublicId,
        Long orderItemId,
        ClaimType claimType,
        ClaimStatus status,
        LocalDateTime occurredAt) {
}
