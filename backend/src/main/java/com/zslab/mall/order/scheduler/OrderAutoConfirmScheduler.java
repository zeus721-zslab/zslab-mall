package com.zslab.mall.order.scheduler;

import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.delivery.service.ReturnWindowPolicy;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.service.OrderAutoConfirmService;
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
 * 원 발송 배송완료 후 {@link ReturnWindowPolicy#WINDOW_DAYS}일이 지난 DELIVERED 품목을 주기적으로 자동 구매확정하는 배치 스케줄러
 * (Track 81-B D-171·R7·OrderAutoCancelScheduler 원형 복제). 트랜잭션을 갖지 않으며 오케스트레이션만 담당한다 — 후보를 한 배치
 * (최대 {@link #BATCH_SIZE}건) 조회한 뒤 id별로 {@link OrderAutoConfirmService#confirmOne}(각자 독립 트랜잭션)을 호출한다.
 *
 * <p><b>부분 실패 격리</b>: id 단위 try/catch로 한 건 실패가 배치 전체를 중단시키지 않는다. {@link Exception}만 흡수하고 {@link Error}는
 * 전파한다.
 *
 * <p><b>발화 억제(테스트·운영 킬스위치)</b>: {@code zslab.order.auto-confirm.enabled=false}면 본 빈이 생성되지 않아 {@code @Scheduled}가
 * 비활성된다(기본 활성·auto-cancel 킬스위치 정합).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zslab.order.auto-confirm.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OrderAutoConfirmScheduler {

    /** 1회 배치 처리 상한(락 보유 시간·1틱 부하 제어). */
    private static final int BATCH_SIZE = 100;

    /** 자동 구매확정 배치 실행 간격(1시간). 직전 실행 종료 후 고정 지연. */
    private static final long FIXED_DELAY_MS = 60 * 60 * 1000L;

    private final DeliveryRepository deliveryRepository;
    private final OrderAutoConfirmService orderAutoConfirmService;

    /**
     * 자동 확정 후보를 한 배치 조회해 id별로 {@link OrderAutoConfirmService#confirmOne}을 호출한다. 배치 조회와 각 건의 확정은 서로 다른
     * 트랜잭션에서 수행된다.
     */
    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void confirmBatch() {
        String schedulerRunId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.minusDays(ReturnWindowPolicy.WINDOW_DAYS);

        List<Long> targetIds = deliveryRepository.findAutoConfirmCandidateOrderItemIds(
                DeliveryDirection.OUTBOUND, DeliveryStatus.DELIVERED, threshold, OrderItemStatus.DELIVERED,
                PageRequest.of(0, BATCH_SIZE));

        if (targetIds.isEmpty()) {
            log.debug("[OrderAutoConfirm] schedulerRunId={} 자동 확정 대상 없음", schedulerRunId);
            return;
        }

        int success = 0;
        int skipped = 0;
        int failed = 0;
        for (Long orderItemId : targetIds) {
            try {
                if (orderAutoConfirmService.confirmOne(orderItemId, now)) {
                    success++;
                } else {
                    skipped++;
                }
            } catch (Exception exception) {
                // Error(OOM 등)는 흡수하지 않고 전파한다. RuntimeException 1건 실패는 격리 후 다음 건을 계속 처리한다.
                failed++;
                log.error("[OrderAutoConfirm] schedulerRunId={} confirmOne 실패 orderItemId={} — 격리 후 진행",
                        schedulerRunId, orderItemId, exception);
            }
        }

        log.info("[OrderAutoConfirm] schedulerRunId={} 배치 완료 대상={} 성공={} skip={} 실패={}",
                schedulerRunId, targetIds.size(), success, skipped, failed);
    }
}
