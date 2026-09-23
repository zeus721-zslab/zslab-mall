package com.zslab.mall.reconciliation.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.reconciliation.repository.ReconciliationCandidate;
import com.zslab.mall.reconciliation.service.ReconciliationCheckPattern;
import com.zslab.mall.reconciliation.service.ReconciliationCheckService;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 점검 스케줄러 커서 순회(Track 104-2 D-216·외부 검토 지적 5·{@code DeliveryAutoCompleteSchedulerTest} 방식). 한 실행 상한을 넘는 후보는
 * 다음 실행이 (패턴, 대상 id) 커서부터 이어받고, 기록되지 않고 남는 후보가 뒤 후보를 가리지 않으며, 끝에 도달하면 커서가 처음으로 돌아가는지 본다.
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationCheckSchedulerTest {

    private static final ReconciliationCheckPattern FIRST = ReconciliationCheckPattern.values()[0];
    private static final ReconciliationCheckPattern SECOND = ReconciliationCheckPattern.values()[1];

    @Mock
    private ReconciliationCheckService reconciliationCheckService;

    private ReconciliationCheckScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ReconciliationCheckScheduler(reconciliationCheckService);
    }

    @Test
    @DisplayName("상한 초과: 첫 패턴 후보가 끝없이 가득 차면 한 실행은 MAX_PER_RUN건에서 멈추고, 다음 실행은 같은 패턴의 그 id 뒤부터 이어받는다")
    void overLimit_nextRunResumesFromCursor() {
        when(reconciliationCheckService.findCandidates(eq(FIRST), anyLong(), anyInt()))
                .thenAnswer(invocation -> page(invocation.getArgument(1), invocation.getArgument(2)));

        scheduler.checkBatch();

        verify(reconciliationCheckService, times(ReconciliationCheckScheduler.MAX_PER_RUN)).recordIfStillPresent(eq(FIRST), any());
        verify(reconciliationCheckService, never()).findCandidates(eq(SECOND), anyLong(), anyInt());

        clearInvocations(reconciliationCheckService);
        scheduler.checkBatch();

        InOrder order = inOrder(reconciliationCheckService);
        order.verify(reconciliationCheckService)
                .findCandidates(FIRST, ReconciliationCheckScheduler.MAX_PER_RUN, ReconciliationCheckScheduler.PAGE_SIZE);
    }

    @Test
    @DisplayName("남는 후보 비차단: 기록되지 않는(재확인 해소) 가득 찬 첫 페이지 뒤의 후보도 같은 실행에서 커서로 이어 조회·기록된다")
    void unrecordedCandidates_doNotHideLaterOnes() {
        int pageSize = ReconciliationCheckScheduler.PAGE_SIZE;
        when(reconciliationCheckService.findCandidates(eq(FIRST), anyLong(), anyInt())).thenAnswer(invocation -> {
            long afterId = invocation.getArgument(1);
            return afterId == 0L ? page(0L, pageSize) : afterId == pageSize ? page(pageSize, 1) : List.of();
        });
        when(reconciliationCheckService.recordIfStillPresent(eq(FIRST), any()))
                .thenAnswer(invocation -> ((ReconciliationCandidate) invocation.getArgument(1)).getTargetId() > pageSize);

        scheduler.checkBatch();

        verify(reconciliationCheckService).findCandidates(FIRST, pageSize, pageSize);
        verify(reconciliationCheckService, times(pageSize + 1)).recordIfStillPresent(eq(FIRST), any());
    }

    @Test
    @DisplayName("끝 도달: 모든 패턴을 끝까지 훑으면 커서가 처음(첫 패턴·0)으로 돌아가 다음 실행이 처음부터 시작한다")
    void reachedEnd_cursorResetsToStart() {
        when(reconciliationCheckService.findCandidates(any(), anyLong(), anyInt())).thenAnswer(invocation ->
                invocation.getArgument(0) == FIRST && (long) invocation.getArgument(1) == 0L ? page(0L, 3) : List.of());

        scheduler.checkBatch();

        for (ReconciliationCheckPattern pattern : ReconciliationCheckPattern.values()) {
            verify(reconciliationCheckService).findCandidates(pattern, 0L, ReconciliationCheckScheduler.PAGE_SIZE);
        }

        clearInvocations(reconciliationCheckService);
        scheduler.checkBatch();

        InOrder order = inOrder(reconciliationCheckService);
        order.verify(reconciliationCheckService).findCandidates(FIRST, 0L, ReconciliationCheckScheduler.PAGE_SIZE);
    }

    /** afterId 뒤로 연속된 id size건. */
    private static List<ReconciliationCandidate> page(long afterId, int size) {
        return LongStream.rangeClosed(afterId + 1, afterId + size).<ReconciliationCandidate>mapToObj(TestCandidate::new).toList();
    }

    /** 대상 id만 있는 후보(스케줄러는 대상 id만 읽는다). */
    private record TestCandidate(long id) implements ReconciliationCandidate {
        @Override public Long getTargetId() { return id; }
        @Override public Long getOrderId() { return null; }
        @Override public Long getPaymentId() { return null; }
        @Override public Long getClaimId() { return null; }
        @Override public Long getDeliveryId() { return null; }
        @Override public Long getOrderItemId() { return null; }
        @Override public String getPgTid() { return null; }
        @Override public String getPaymentStatus() { return null; }
        @Override public Long getPaymentAmount() { return null; }
        @Override public Long getRefundedAmount() { return null; }
        @Override public String getItemStatus() { return null; }
        @Override public String getClaimType() { return null; }
        @Override public String getClaimStatus() { return null; }
        @Override public String getDeliveryStatus() { return null; }
    }
}
