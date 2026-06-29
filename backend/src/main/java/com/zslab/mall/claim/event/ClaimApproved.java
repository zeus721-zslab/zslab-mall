package com.zslab.mall.claim.event;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import java.time.LocalDateTime;

/**
 * 클레임 승인 도메인 이벤트(D-30 사실 통지·QB-13 record 패턴). Spring ApplicationEvent로 발행한다.
 *
 * <p>발행 시점은 {@code ClaimService.approve}의 save 직후다(D-29 save→publish·no flush). Track 10부터는 Seller
 * 진입점 {@code ClaimService.approveBySeller}(D-92 Q3-sub a‴) 경유로도 동일 primitive에서 발행된다. Track 10-B부터는
 * Admin 진입점 {@code ClaimService.approveByAdmin}(D-93 Q3·전체 접근·권한 검증 단락 부재) 경유에서도 동일 primitive에서
 * 발행된다.
 *
 * <p>소비자는 아직 부재다. D-92 Q8 β에 따라 Refund 자동 트리거는 본 트랙 범위 외이며, 후속 트랙(Refund Service
 * 진입 시점)에서 {@code ClaimApproved → RefundCreated} 자동 변환을 구성할 예정이다. NotificationLog 도메인 진입
 * 시점에는 Seller 승인 알림 source로도 소비 가능하다. Track 10 범위에서는 발행만 보장하고 소비 검증은 수행하지 않는다.
 */
public record ClaimApproved(
        Long claimId,
        String claimPublicId,
        Long orderItemId,
        ClaimType claimType,
        ClaimStatus status,
        LocalDateTime occurredAt) {
}
