package com.zslab.mall.claim.service;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 클레임 Application Service(최소·Track 5·expected-spec §5.3). 본 트랙은 종결 전이 1건만 담당한다.
 *
 * <p>요청·승인/거절 워크플로우는 후속 트랙 소관이며(expected-spec §1.2), 본 서비스의 {@link #markCompleted}는
 * Refund.COMPLETED 이벤트 핸들러에서 호출된다.
 */
@Slf4j
@Service
@Transactional
public class ClaimService {

    private final ClaimRepository claimRepository;

    public ClaimService(ClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    /**
     * 클레임을 종결한다(APPROVED → COMPLETED·CLM-4). 이미 COMPLETED면 멱등 no-op이다(CLM-1).
     *
     * <p>호출 맥락은 {@code ClaimRefundCompletedHandler}(Claim.type=CANCEL·AFTER_COMMIT)다. 처리 시각은 시스템 시각으로 채운다.
     *
     * @param claimId 종결 대상 클레임 id
     * @throws ClaimNotFoundException 클레임이 없는 경우
     * @throws IllegalStateException  APPROVED·COMPLETED가 아닌 상태에서 호출된 경우(CLM-1·CLM-4)
     */
    public void markCompleted(Long claimId) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
        if (claim.getStatus() == ClaimStatus.COMPLETED) {
            log.info("[Claim] markCompleted 멱등 NO-OP(이미 COMPLETED): claimId={}", claimId);
            return;
        }
        claim.markCompleted(LocalDateTime.now());
        claimRepository.save(claim);
    }
}
