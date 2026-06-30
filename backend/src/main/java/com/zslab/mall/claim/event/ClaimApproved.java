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
 * <p>소비자는 Track 11(D-94)에서 신설되었다. {@code refund/handler/ClaimApprovedHandler}가 본 이벤트를 소비하며
 * CANCEL type 한정으로 {@code RefundService.initiate}를 자동 트리거한다(D-87 Q3 → D-90 Q2 → D-92 Q8 → D-93 Q9
 * carry-over 종결). Track 12 {@code notification/handler/NotificationClaimApprovedHandler}가 본 이벤트를 4번째
 * 소비자로 추가 소비해 클레임 승인 알림을 적재한다(D-95 Q4·산정 실패 시 skip + structured log·A1-α).
 */
public record ClaimApproved(
        Long claimId,
        String claimPublicId,
        Long orderItemId,
        ClaimType claimType,
        ClaimStatus status,
        LocalDateTime occurredAt) {
}
