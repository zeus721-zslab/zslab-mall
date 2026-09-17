package com.zslab.mall.refund.scheduler;

import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.refund.service.RefundRecoveryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 환불 누락 복구 배치(D-172·Q2-a·외부 검토 A). 승인/검수 PASS 커밋 후 AFTER_COMMIT 환불 핸들러가 실패·유실돼 Refund 행이 없는 클레임을
 * 주기적으로 찾아 {@link RefundRecoveryService#recoverMissingRefund}로 환불을 시작한다. 환불 시작 자체를 승인 TX에 동기화하지 않는 이유는
 * PG 호출을 DB 트랜잭션 안에 두지 않기 위해서다(α 기각). 트랜잭션을 갖지 않으며 오케스트레이션만 담당한다(OrderAutoCancelScheduler 원형).
 *
 * <p><b>대상·유예</b>: CANCEL APPROVED(processedAt) 또는 RETURN 검수 PASS(inspectedAt) 후 {@value #GRACE_MINUTES}분 경과·Refund 행 0건.
 * FAILED 행이 있는 클레임은 관리자 재시도 경로(RFN-2)라 제외된다. 부분 실패는 id별 try/catch로 격리한다.
 *
 * <p><b>발화 억제</b>: {@code zslab.refund.recovery.enabled=false}면 본 빈이 생성되지 않는다(기본 활성·auto-cancel 킬스위치 정합).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zslab.refund.recovery.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RefundRecoveryScheduler {

    /** 1회 배치 처리 상한. */
    static final int BATCH_SIZE = 100;

    /** 복구 배치 실행 간격(10분). 직전 실행 종료 후 고정 지연. */
    private static final long FIXED_DELAY_MS = 10 * 60 * 1000L;

    /** 승인/검수 후 환불 행이 없어도 "누락"으로 보지 않는 유예(AFTER_COMMIT 핸들러 정상 처리 시간). */
    static final long GRACE_MINUTES = 5L;

    private final ClaimRepository claimRepository;
    private final RefundRecoveryService refundRecoveryService;

    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void recoverBatch() {
        String schedulerRunId = UUID.randomUUID().toString();
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(GRACE_MINUTES);
        List<Long> claimIds = claimRepository.findRefundMissingClaimIds(threshold, PageRequest.of(0, BATCH_SIZE));
        if (claimIds.isEmpty()) {
            log.debug("[RefundRecovery] schedulerRunId={} 환불 누락 대상 없음", schedulerRunId);
            return;
        }
        int success = 0;
        int failed = 0;
        for (Long claimId : claimIds) {
            try {
                refundRecoveryService.recoverMissingRefund(claimId);
                success++;
            } catch (Exception exception) {
                // Error(OOM 등)는 흡수하지 않고 전파한다. RuntimeException 1건 실패는 격리 후 다음 건을 계속 처리한다.
                failed++;
                log.error("[RefundRecovery] schedulerRunId={} 환불 누락 복구 실패 claimId={} — 격리 후 진행", schedulerRunId, claimId, exception);
            }
        }
        log.info("[RefundRecovery] schedulerRunId={} 배치 완료 대상={} 성공={} 실패={}", schedulerRunId, claimIds.size(), success, failed);
    }
}
