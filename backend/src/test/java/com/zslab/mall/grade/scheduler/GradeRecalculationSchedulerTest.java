package com.zslab.mall.grade.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.grade.service.GradeRecalculationBatchService;
import com.zslab.mall.grade.service.GradeRecalculationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link GradeRecalculationScheduler} 단위 검증(Mockito·Track 96-6). 배치 서비스 위임·순회 진입 전 예외 흡수·Error 미흡수를 커버한다.
 * 킬스위치·탈퇴 제외·잠금 규칙은 통합 테스트가 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class GradeRecalculationSchedulerTest {

    @Mock
    private GradeRecalculationBatchService gradeRecalculationBatchService;
    @InjectMocks
    private GradeRecalculationScheduler scheduler;

    @Test
    @DisplayName("정상: recalculateAll 1회 위임")
    void recalculateBatch_delegates() {
        when(gradeRecalculationBatchService.recalculateAll()).thenReturn(new GradeRecalculationResult(3, 3, 0));

        scheduler.recalculateBatch();

        verify(gradeRecalculationBatchService).recalculateAll();
    }

    @Test
    @DisplayName("순회 진입 전 예외(대상 조회 실패): 흡수·로그 후 정상 종료")
    void exception_isAbsorbed() {
        when(gradeRecalculationBatchService.recalculateAll()).thenThrow(new IllegalStateException("대상 조회 실패"));

        assertThatCode(() -> scheduler.recalculateBatch()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Error(OOM 등)는 흡수하지 않고 전파")
    void error_isPropagated() {
        when(gradeRecalculationBatchService.recalculateAll()).thenThrow(new OutOfMemoryError("치명 오류"));

        assertThatThrownBy(() -> scheduler.recalculateBatch()).isInstanceOf(OutOfMemoryError.class);
    }
}
