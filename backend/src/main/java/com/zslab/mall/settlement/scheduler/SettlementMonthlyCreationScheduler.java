package com.zslab.mall.settlement.scheduler;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.settlement.service.SettlementBatchResult;
import com.zslab.mall.settlement.service.SettlementCreationService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 전월 정산을 자동 생성하는 배치 스케줄러(Track 96-6·OrderAutoConfirmScheduler 원형 준용). 트랜잭션을 갖지 않으며 오케스트레이션만
 * 담당한다 — 실행일 기준 전월을 산정해 {@link SettlementCreationService#createMonthlySettlements}(단일 트랜잭션·셀러별 멱등 skip)를
 * 시스템 행위자({@link AuditContext#system()})로 호출한다. 이미 생성된 셀러는 서비스가 skip하므로 24시간마다 재실행해도 중복이 없고,
 * 확정·지급은 관리자 수동 전이 그대로다.
 *
 * <p><b>실패 처리</b>: 서비스 예외(동시 실행 레이스 409 포함)는 {@link Exception} 단위로 흡수해 {@code log.error} 후 종료한다 — 다음
 * 실행에서 같은 전월을 다시 시도한다. {@link Error}(OOM 등)는 흡수하지 않는다.
 *
 * <p><b>발화 억제(테스트·운영 킬스위치)</b>: {@code zslab.settlement.monthly-creation.enabled=false}면 본 빈이 생성되지 않아
 * {@code @Scheduled}가 비활성된다(기본 활성·기존 스케줄러 킬스위치 관례).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zslab.settlement.monthly-creation.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SettlementMonthlyCreationScheduler {

    /** 전월 정산 생성 배치 실행 간격(24시간·일 배치). 직전 실행 종료 후 고정 지연. */
    private static final long FIXED_DELAY_MS = 24 * 60 * 60 * 1000L;

    private final SettlementCreationService settlementCreationService;

    /** 실행일(JVM 기본 시간대 오늘) 기준 전월 정산을 생성한다. */
    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void createPreviousMonthBatch() {
        createForPreviousMonthOf(LocalDate.now());
    }

    /**
     * {@code today} 기준 전월 정산을 생성한다. 1월이면 전년 12월.
     *
     * @param today 실행일
     */
    public void createForPreviousMonthOf(LocalDate today) {
        String schedulerRunId = UUID.randomUUID().toString();
        YearMonth previousMonth = YearMonth.from(today).minusMonths(1);
        try {
            SettlementBatchResult result = settlementCreationService.createMonthlySettlements(
                    previousMonth.getYear(), previousMonth.getMonthValue(), AuditContext.system());
            log.info("[SettlementMonthly] schedulerRunId={} 배치 완료 period={} 생성={}",
                    schedulerRunId, previousMonth, result.created().size());
        } catch (Exception exception) {
            // Error(OOM 등)는 흡수하지 않고 전파한다. 서비스 예외(레이스 409 등)는 기록 후 종료·다음 실행에서 재시도한다.
            log.error("[SettlementMonthly] schedulerRunId={} 전월 정산 생성 실패 period={} — 다음 실행에서 재시도",
                    schedulerRunId, previousMonth, exception);
        }
    }
}
