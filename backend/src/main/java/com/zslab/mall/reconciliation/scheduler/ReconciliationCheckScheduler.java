package com.zslab.mall.reconciliation.scheduler;

import com.zslab.mall.reconciliation.repository.ReconciliationCandidate;
import com.zslab.mall.reconciliation.service.ReconciliationCheckPattern;
import com.zslab.mall.reconciliation.service.ReconciliationCheckService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 저장된 행으로 판정 가능한 불일치(U5·U6·U7·U10~U13)를 하루 1회 찾아 기록하는 점검 스케줄러(Track 104-2 D-216·invariants P6·
 * {@code GradeRecalculationScheduler} 원형 준용). 트랜잭션을 갖지 않으며 패턴별로 후보를 페이지로 읽고 건마다
 * {@link ReconciliationCheckService#recordIfStillPresent}(각자 독립 트랜잭션)에 넘긴다. 업무 데이터는 바꾸지 않는다.
 *
 * <p><b>커서 순회(외부 검토 지적 5·{@code DeliveryAutoCompleteScheduler} 방식)</b>: 패턴 순서대로 대상 id 커서로 {@link #PAGE_SIZE}건씩 끝까지
 * 훑는다. 기록되지 않고 남는 후보(재확인에서 풀림·기록 실패)는 다음 페이지 조회에서 커서 뒤로 밀려나 뒤 후보를 가리지 않는다. 한 실행에서
 * 훑는 후보는 모든 패턴을 합쳐 {@link #MAX_PER_RUN}건까지이고, 상한에 걸리면 (패턴, 대상 id) 커서를 인스턴스 필드에 남겨 다음 실행이
 * 그 위치부터 이어받는다. 마지막 패턴 끝에 도달하면 커서를 처음(첫 패턴·0)으로 되돌린다.
 *
 * <p><b>단일 인스턴스 전제</b>: 커서는 인스턴스 필드다(기존 스케줄러와 같은 전제·D-210 §8). 재기동하면 처음부터 다시 훑는다 — 기록은 멱등이라
 * 중복 조회 비용만 든다. {@code @Scheduled}는 한 번에 하나씩만 실행되므로 동시 접근이 없다.
 *
 * <p><b>부분 실패 격리</b>: 건 단위·페이지 조회 단위 try/catch로 한 건 실패가 배치 전체를 멈추지 않는다. {@link Exception}만 흡수하고
 * {@link Error}는 전파한다.
 *
 * <p><b>발화 억제(테스트·운영 킬스위치)</b>: {@code zslab.reconciliation.check.enabled=false}면 본 빈이 생성되지 않는다(기본 활성·기존 관례).
 * 테스트는 {@code AbstractIntegrationTest}가 전역으로 끈다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zslab.reconciliation.check.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ReconciliationCheckScheduler {

    /** 1회 조회 페이지 크기(패턴별). */
    static final int PAGE_SIZE = 200;

    /** 1회 실행에서 훑는 후보 상한(모든 패턴 합계·1틱 부하 제어). 남은 후보는 다음 실행이 커서부터 이어받는다. */
    static final int MAX_PER_RUN = 2000;

    /** 점검 실행 간격(24시간·일 배치). 직전 실행 종료 후 고정 지연. */
    private static final long FIXED_DELAY_MS = 24 * 60 * 60 * 1000L;

    private static final ReconciliationCheckPattern[] PATTERNS = ReconciliationCheckPattern.values();

    private final ReconciliationCheckService reconciliationCheckService;

    /** 다음 실행이 시작할 패턴 위치(PATTERNS 인덱스). 끝까지 훑으면 0으로 되돌아간다. */
    private int cursorPatternIndex = 0;

    /** 다음 실행이 시작할 대상 id(이 값보다 큰 id부터). 패턴이 바뀌면 0이다. */
    private long cursorTargetId = 0L;

    /** 커서 위치부터 패턴 끝까지(상한 안에서) 차례로 점검한다. */
    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void checkBatch() {
        String schedulerRunId = UUID.randomUUID().toString();
        String start = cursorText();
        int scanned = 0;
        int recorded = 0;
        int skipped = 0;
        int failed = 0;

        while (cursorPatternIndex < PATTERNS.length && scanned < MAX_PER_RUN) {
            ReconciliationCheckPattern pattern = PATTERNS[cursorPatternIndex];
            int limit = Math.min(PAGE_SIZE, MAX_PER_RUN - scanned);
            List<ReconciliationCandidate> page;
            try {
                page = reconciliationCheckService.findCandidates(pattern, cursorTargetId, limit);
            } catch (Exception exception) {
                // Error는 흡수하지 않는다. 조회 실패는 이 패턴만 건너뛰고 다음 순회에서 다시 훑는다.
                log.error("[ReconciliationCheck] schedulerRunId={} 패턴 {} 조회 실패 — 다음 패턴으로 진행", schedulerRunId, pattern, exception);
                moveToNextPattern();
                continue;
            }
            for (ReconciliationCandidate candidate : page) {
                cursorTargetId = candidate.getTargetId();
                scanned++;
                try {
                    if (reconciliationCheckService.recordIfStillPresent(pattern, candidate)) {
                        recorded++;
                    } else {
                        skipped++;
                    }
                } catch (Exception exception) {
                    // 1건 실패는 격리 후 다음 건을 계속 처리한다(미기록 대상은 다음 순회 후보로 다시 잡힌다).
                    failed++;
                    log.error("[ReconciliationCheck] schedulerRunId={} 패턴 {} 기록 실패 targetId={} — 격리 후 진행",
                            schedulerRunId, pattern, candidate.getTargetId(), exception);
                }
            }
            if (page.size() < limit) {
                // 이 패턴의 마지막 페이지까지 훑었다 → 다음 패턴은 처음(0)부터.
                moveToNextPattern();
            }
        }

        boolean reachedEnd = cursorPatternIndex >= PATTERNS.length;
        if (reachedEnd) {
            cursorPatternIndex = 0;
            cursorTargetId = 0L;
        }
        log.info("[ReconciliationCheck] schedulerRunId={} 배치 완료 조회={} 기록={} skip={} 실패={} 커서 {}→{}{}",
                schedulerRunId, scanned, recorded, skipped, failed, start, cursorText(),
                reachedEnd ? "(끝 도달·다음 실행은 처음부터)" : "(상한 도달·다음 실행이 이어받음)");
    }

    private void moveToNextPattern() {
        cursorPatternIndex++;
        cursorTargetId = 0L;
    }

    private String cursorText() {
        String pattern = cursorPatternIndex < PATTERNS.length ? PATTERNS[cursorPatternIndex].name() : "END";
        return pattern + "/" + cursorTargetId;
    }
}
