package com.zslab.mall.order.scheduler;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.order.service.ExpiredOrderCleanupService;
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
 * 유예(GRACE_DAYS) 경과한 미결제 종료(PAYMENT_EXPIRED) 주문을 주기적으로 hard delete하는 배치 스케줄러(FE-12c-2·
 * OrderAutoCancelScheduler 원형 복제). 트랜잭션을 갖지 않으며 오케스트레이션만 담당한다 — 삭제 후보를 한 배치
 * (최대 {@link #BATCH_SIZE}건) 조회한 뒤 id별로 {@link ExpiredOrderCleanupService#cleanupOne}(각자 독립 트랜잭션)을 호출한다.
 *
 * <p><b>부분 실패 격리</b>: id 단위 try/catch로 한 건 실패가 배치 전체를 중단시키지 않는다(로그 후 다음 건 진행).
 * {@link Exception}만 흡수하고 {@link Error}(OOM 등)는 흡수하지 않아 JVM 치명 오류는 그대로 전파된다.
 *
 * <p><b>발화 억제(테스트·운영 킬스위치)</b>: {@code zslab.order.expired-cleanup.enabled=false}면 본 빈이 생성되지 않아
 * {@code @Scheduled}가 비활성된다(기본 활성·OrderAutoCancelScheduler 킬스위치 정합). 프로젝트에 test profile이 없어
 * {@code @SpringBootTest}가 스케줄러를 자동 발화시키는 것을 막고, 되돌릴 수 없는 삭제의 운영 긴급 정지 수단을 겸한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zslab.order.expired-cleanup.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ExpiredOrderCleanupScheduler {

    /** 1회 배치 처리 상한(락 보유 시간·1틱 부하 제어). */
    private static final int BATCH_SIZE = 100;

    /** 삭제 배치 실행 간격(24시간·일 배치). 직전 실행 종료 후 고정 지연. */
    private static final long FIXED_DELAY_MS = 24 * 60 * 60 * 1000L;

    /** 미결제 종료 후 삭제 유예(7일·FE-12c-2). updatedAt(종료 시각 근사)이 now-유예 이전인 PAYMENT_EXPIRED가 대상이다. */
    private static final long GRACE_DAYS = 7L;

    private final OrderRepository orderRepository;
    private final ExpiredOrderCleanupService expiredOrderCleanupService;

    /**
     * 삭제 후보를 한 배치 조회해 id별로 {@link ExpiredOrderCleanupService#cleanupOne}을 호출한다. 본 메서드는 트랜잭션이 없어
     * 배치 조회와 각 건의 삭제가 서로 다른 트랜잭션에서 수행된다.
     */
    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void cleanupBatch() {
        String schedulerRunId = UUID.randomUUID().toString();
        LocalDateTime threshold = LocalDateTime.now().minusDays(GRACE_DAYS);

        List<Long> targetIds = orderRepository
                .findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
                        OrderStatus.PAYMENT_EXPIRED, threshold, PageRequest.of(0, BATCH_SIZE))
                .stream()
                .map(Order::getId)
                .toList();

        if (targetIds.isEmpty()) {
            log.debug("[ExpiredCleanup] schedulerRunId={} 삭제 대상 없음", schedulerRunId);
            return;
        }

        int success = 0;
        int failed = 0;
        for (Long orderId : targetIds) {
            try {
                expiredOrderCleanupService.cleanupOne(orderId);
                success++;
            } catch (Exception exception) {
                // Error(OOM 등)는 흡수하지 않고 전파한다. RuntimeException 1건 실패는 격리 후 다음 건을 계속 처리한다.
                failed++;
                log.error("[ExpiredCleanup] schedulerRunId={} cleanupOne 실패 orderId={} — 격리 후 진행",
                        schedulerRunId, orderId, exception);
            }
        }

        log.info("[ExpiredCleanup] schedulerRunId={} 배치 완료 대상={} 성공={} 실패={}",
                schedulerRunId, targetIds.size(), success, failed);
    }
}
