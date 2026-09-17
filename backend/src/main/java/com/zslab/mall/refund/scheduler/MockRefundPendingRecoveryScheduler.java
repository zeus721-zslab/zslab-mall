package com.zslab.mall.refund.scheduler;

import com.zslab.mall.payment.gateway.MockPaymentGateway;
import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.refund.repository.RefundRepository;
import com.zslab.mall.refund.service.RefundRecoveryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mock PG 한정 환불 완료 콜백 복구 배치(D-172·Q2-a). {@code MockRefundAutoCallbackListener}(AFTER_COMMIT)가 실패·유실돼 PENDING으로 남은
 * 환불에 SUCCESS 콜백을 다시 일으킨다. 실 PG는 자체 웹훅 재전송이 있으므로 {@link MockPaymentGateway} 빈이 있을 때만 등록된다
 * ({@code @ConditionalOnBean}·MockRefundAutoCallbackListener와 동일 조건). 킬스위치는 환불 누락 복구와 같은 {@code zslab.refund.recovery.enabled}.
 *
 * <p><b>대상·유예</b>: 생성 후 {@value RefundRecoveryScheduler#GRACE_MINUTES}분 경과한 PENDING(pg_refund_id 보유) — FAILED는 제외.
 * 중복 콜백은 {@code RefundService.handleCallback}의 환불 행 락 + 종결 no-op이 흡수한다.
 */
@Slf4j
@Component
@ConditionalOnBean(MockPaymentGateway.class)
@ConditionalOnProperty(name = "zslab.refund.recovery.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MockRefundPendingRecoveryScheduler {

    private static final long FIXED_DELAY_MS = 10 * 60 * 1000L;

    private final RefundRepository refundRepository;
    private final RefundRecoveryService refundRecoveryService;

    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void replayBatch() {
        String schedulerRunId = UUID.randomUUID().toString();
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(RefundRecoveryScheduler.GRACE_MINUTES);
        List<Refund> pending = refundRepository.findByStatusAndCreatedAtLessThanEqualOrderByIdAsc(
                RefundStatus.PENDING, threshold, PageRequest.of(0, RefundRecoveryScheduler.BATCH_SIZE));
        if (pending.isEmpty()) {
            log.debug("[RefundRecovery] schedulerRunId={} Mock PENDING 대상 없음", schedulerRunId);
            return;
        }
        int success = 0;
        int failed = 0;
        for (Refund refund : pending) {
            if (refund.getPgRefundId() == null) {
                // PG 요청 등록 전 상태(initiate 진행 중 또는 예외 직전) — 콜백 키가 없어 재발생 불가·다음 배치에서 재판정
                continue;
            }
            try {
                refundRecoveryService.replayMockSuccessCallback(refund.getPgRefundId());
                success++;
            } catch (Exception exception) {
                failed++;
                log.error("[RefundRecovery] schedulerRunId={} Mock 콜백 재발생 실패 refundId={} — 격리 후 진행", schedulerRunId, refund.getId(), exception);
            }
        }
        log.info("[RefundRecovery] schedulerRunId={} Mock PENDING 배치 완료 대상={} 성공={} 실패={}", schedulerRunId, pending.size(), success, failed);
    }
}
