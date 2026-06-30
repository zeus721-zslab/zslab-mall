package com.zslab.mall.claim.event;

import com.zslab.mall.claim.enums.ClaimType;
import java.time.LocalDateTime;

/**
 * 클레임 수거 확인 도메인 이벤트(E11 신규 채번·D-98 Q1). Spring ApplicationEvent로 발행한다.
 *
 * <p>RETURN/EXCHANGE 클레임의 "수거 완료" 사실 통지다(D-30 사실 통지). 상태 전이 이벤트가 아니라 milestone
 * 통지이므로 {@code status} 필드를 생략한다 — 소비측은 {@code claimId}로 Claim을 재조회한다(D-30 정합).
 * 핵심 payload는 {@code pickedUpAt}(수거 확인 시각)이다.
 *
 * <p>발행 주체는 Seller 우선·Admin override이며 외부 택배 어댑터 연동은 후속 트랙이다(D-98 Q1). 발행 시점은
 * {@code ClaimService.confirmPickup}의 save 직후다(D-29 save→publish·no flush·STEP 4 신설 예정). 멱등성은
 * {@code claim.picked_up_at} 가드로 보장한다(이미 수거 확인된 경우 no-op·D-98 Q1·STEP 4 Claim.confirmPickup).
 *
 * <p>소비자는 본 트랙에서 신설 예정이다(STEP 5): {@code refund/handler/ClaimPickedUpHandler}가 RETURN type
 * 한정으로 {@code RefundService.initiate}를 자동 트리거하고(D-98 Q2), {@code
 * notification/handler/NotificationClaimPickedUpHandler}가 PICKUP_CONFIRMED 알림을 비동기 적재한다(D-98 Q8).
 */
public record ClaimPickedUp(
        Long claimId,
        String claimPublicId,
        Long orderItemId,
        ClaimType claimType,
        LocalDateTime pickedUpAt,
        LocalDateTime occurredAt) {
}
