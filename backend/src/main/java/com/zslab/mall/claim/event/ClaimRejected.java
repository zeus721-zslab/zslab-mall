package com.zslab.mall.claim.event;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import java.time.LocalDateTime;

/**
 * 클레임 거절 도메인 이벤트(D-30 사실 통지·QB-13 record 패턴). Spring ApplicationEvent로 발행한다.
 *
 * <p>발행 시점은 {@code ClaimService.reject}의 save 직후다(D-29 save→publish·no flush). 소비측 핸들러
 * (OrderItem 상태 복귀 등)는 Track 9 PR-C 소관이며 본 PR은 발행만 한다. 거절 후 재요청은 새 Claim 행이다(CLM-2).
 */
public record ClaimRejected(
        Long claimId,
        String claimPublicId,
        Long orderItemId,
        ClaimType claimType,
        ClaimStatus status,
        LocalDateTime occurredAt) {
}
