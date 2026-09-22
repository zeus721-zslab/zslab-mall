package com.zslab.mall.grade.scheduler;

import com.zslab.mall.grade.service.GradeRecalculationBatchService;
import com.zslab.mall.grade.service.GradeRecalculationResult;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 구매자 등급을 주기적으로 재산정하는 배치 스케줄러(Track 96-6·OrderAutoConfirmScheduler 원형 준용). 트랜잭션을 갖지 않으며
 * {@link GradeRecalculationBatchService#recalculateAll}(활성 buyer 순회·buyer별 독립 트랜잭션·부분 성공)에 위임만 한다. 관리자 전체
 * 재산정 API와 같은 경로라 탈퇴 회원 제외·등급 잠금 존중·감사 미적재 규칙이 그대로 적용된다.
 *
 * <p><b>실패 처리</b>: buyer 1건 실패는 배치 서비스가 흡수·집계한다. 순회 진입 전 예외(대상 조회 실패 등)는 {@link Exception} 단위로
 * 흡수해 {@code log.error} 후 종료하고 다음 실행에서 재시도한다. {@link Error}는 흡수하지 않는다.
 *
 * <p><b>발화 억제(테스트·운영 킬스위치)</b>: {@code zslab.grade.recalculation.enabled=false}면 본 빈이 생성되지 않아 {@code @Scheduled}가
 * 비활성된다(기본 활성·기존 스케줄러 킬스위치 관례).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zslab.grade.recalculation.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class GradeRecalculationScheduler {

    /** 등급 재산정 배치 실행 간격(24시간·일 배치). 직전 실행 종료 후 고정 지연. */
    private static final long FIXED_DELAY_MS = 24 * 60 * 60 * 1000L;

    private final GradeRecalculationBatchService gradeRecalculationBatchService;

    /** 활성 buyer 전체 등급을 재산정한다. */
    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void recalculateBatch() {
        String schedulerRunId = UUID.randomUUID().toString();
        try {
            GradeRecalculationResult result = gradeRecalculationBatchService.recalculateAll();
            log.info("[GradeRecalculation] schedulerRunId={} 배치 완료 대상={} 성공={} 실패={}",
                    schedulerRunId, result.total(), result.success(), result.failure());
        } catch (Exception exception) {
            // Error(OOM 등)는 흡수하지 않고 전파한다. 순회 진입 전 예외는 기록 후 종료·다음 실행에서 재시도한다.
            log.error("[GradeRecalculation] schedulerRunId={} 등급 재산정 배치 실패 — 다음 실행에서 재시도",
                    schedulerRunId, exception);
        }
    }
}
