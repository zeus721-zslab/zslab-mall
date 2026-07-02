package com.zslab.mall.payment.scheduler;

import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.repository.PaymentRepository;
import com.zslab.mall.payment.service.ExpirePaymentService;
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
 * 만료된 PENDING 결제를 주기적으로 FAILED로 전이시키는 배치 스케줄러(Track 25·D-08 M-14). 트랜잭션을 갖지 않으며
 * 오케스트레이션만 담당한다 — 만료 후보를 한 배치(최대 {@link #BATCH_SIZE}건) 조회한 뒤 id별로
 * {@link ExpirePaymentService#expireOne}(각자 독립 트랜잭션)을 호출한다.
 *
 * <p><b>부분 실패 격리</b>: id 단위 try/catch로 한 건 실패가 배치 전체를 중단시키지 않는다(로그 후 다음 건 진행).
 * {@link Exception}만 흡수하고 {@link Error}(OOM 등)는 흡수하지 않아 JVM 치명 오류는 그대로 전파된다.
 *
 * <p><b>발화 억제(테스트·운영 킬스위치)</b>: {@code zslab.payment.expiry.enabled=false}면 본 빈이 생성되지 않아
 * {@code @Scheduled}가 비활성된다(기본 활성). 프로젝트에 test profile이 없어 {@code @SpringBootTest}가 스케줄러를
 * 자동 발화시키는 것을 막고, 운영 긴급 정지 수단을 겸한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zslab.payment.expiry.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ExpirePaymentScheduler {

    /** 1회 배치 처리 상한(락 보유 시간·1틱 부하 제어). */
    private static final int BATCH_SIZE = 100;

    /** 만료 배치 실행 간격(5분). 직전 실행 종료 후 고정 지연. */
    private static final long FIXED_DELAY_MS = 5 * 60 * 1000L;

    private final PaymentRepository paymentRepository;
    private final ExpirePaymentService expirePaymentService;

    /**
     * 만료 후보를 한 배치 조회해 id별로 {@link ExpirePaymentService#expireOne}을 호출한다. 본 메서드는 트랜잭션이 없어
     * 배치 조회와 각 건의 전이가 서로 다른 트랜잭션에서 수행된다.
     */
    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void expireBatch() {
        String schedulerRunId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        List<Long> expiredIds = paymentRepository
                .findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(PaymentStatus.PENDING, now, PageRequest.of(0, BATCH_SIZE))
                .stream()
                .map(Payment::getId)
                .toList();

        if (expiredIds.isEmpty()) {
            log.debug("[PaymentExpiry] schedulerRunId={} 만료 대상 없음", schedulerRunId);
            return;
        }

        int success = 0;
        int failed = 0;
        for (Long paymentId : expiredIds) {
            try {
                expirePaymentService.expireOne(paymentId);
                success++;
            } catch (Exception exception) {
                // Error(OOM 등)는 흡수하지 않고 전파한다. RuntimeException 1건 실패는 격리 후 다음 건을 계속 처리한다.
                failed++;
                log.error("[PaymentExpiry] schedulerRunId={} expireOne 실패 paymentId={} — 격리 후 진행",
                        schedulerRunId, paymentId, exception);
            }
        }

        log.info("[PaymentExpiry] schedulerRunId={} 배치 완료 대상={} 성공={} 실패={}",
                schedulerRunId, expiredIds.size(), success, failed);
    }
}
