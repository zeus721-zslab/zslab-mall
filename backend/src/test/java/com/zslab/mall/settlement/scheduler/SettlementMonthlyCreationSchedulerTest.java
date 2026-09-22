package com.zslab.mall.settlement.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.settlement.exception.SettlementAlreadyExistsException;
import com.zslab.mall.settlement.service.SettlementBatchResult;
import com.zslab.mall.settlement.service.SettlementCreationService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link SettlementMonthlyCreationScheduler} 단위 검증(Mockito·Track 96-6). 전월 산정(연초 경계 포함)·시스템 행위자 전달·
 * 서비스 예외 흡수(다음 실행 재시도)·Error 미흡수를 커버한다. 킬스위치는 통합 테스트가 빈 부재로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SettlementMonthlyCreationSchedulerTest {

    @Mock
    private SettlementCreationService settlementCreationService;
    @InjectMocks
    private SettlementMonthlyCreationScheduler scheduler;

    private static final SettlementBatchResult EMPTY_RESULT =
            new SettlementBatchResult(LocalDateTime.MIN, LocalDateTime.MIN, List.of());

    @Test
    @DisplayName("전월 산정: 2026-09-22 실행 → 2026년 8월·시스템 행위자(actor null·SYSTEM)")
    void previousMonth_midYear() {
        when(settlementCreationService.createMonthlySettlements(eq(2026), eq(8), any())).thenReturn(EMPTY_RESULT);

        scheduler.createForPreviousMonthOf(LocalDate.of(2026, 9, 22));

        ArgumentCaptor<AuditContext> context = ArgumentCaptor.forClass(AuditContext.class);
        verify(settlementCreationService).createMonthlySettlements(eq(2026), eq(8), context.capture());
        assertThat(context.getValue().actorUserId()).isNull();
        assertThat(context.getValue().actorRole())
                .isEqualTo(AuditContext.SYSTEM_ACTOR_ROLE);
    }

    @Test
    @DisplayName("전월 산정: 1월 실행 → 전년 12월")
    void previousMonth_january() {
        when(settlementCreationService.createMonthlySettlements(eq(2025), eq(12), any())).thenReturn(EMPTY_RESULT);

        scheduler.createForPreviousMonthOf(LocalDate.of(2026, 1, 1));

        verify(settlementCreationService).createMonthlySettlements(eq(2025), eq(12), any());
    }

    @Test
    @DisplayName("서비스 예외(레이스 409): 흡수·로그 후 정상 종료(다음 실행 재시도)")
    void serviceException_isAbsorbed() {
        when(settlementCreationService.createMonthlySettlements(eq(2026), eq(8), any()))
                .thenThrow(new SettlementAlreadyExistsException("동시 실행 레이스"));

        assertThatCode(() -> scheduler.createForPreviousMonthOf(LocalDate.of(2026, 9, 22)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Error(OOM 등)는 흡수하지 않고 전파")
    void error_isPropagated() {
        when(settlementCreationService.createMonthlySettlements(eq(2026), eq(8), any()))
                .thenThrow(new OutOfMemoryError("치명 오류"));

        assertThatThrownBy(() -> scheduler.createForPreviousMonthOf(LocalDate.of(2026, 9, 22)))
                .isInstanceOf(OutOfMemoryError.class);
    }
}
