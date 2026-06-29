package com.zslab.mall.claim.event;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import java.time.LocalDateTime;

/**
 * 클레임 요청 도메인 이벤트(D-30 사실 통지·QB-13 record 패턴). Spring ApplicationEvent로 발행한다.
 *
 * <p>발행 시점은 {@code ClaimService.request}의 save 직후다(D-29 save→publish·no flush). 소비측 핸들러
 * (OrderItem.item_status 동기화 등)는 Track 9 PR-C 소관이며 본 PR은 발행만 한다.
 *
 * <p>payload는 식별자·상태·시각으로 한정한다. Claim 엔티티 통째 전달 금지 — 소비측은 식별자로 재조회한다.
 */
public record ClaimRequested(
        Long claimId,
        String claimPublicId,
        Long orderItemId,
        ClaimType claimType,
        ClaimStatus status,
        Long buyerId,
        LocalDateTime occurredAt) {
}
