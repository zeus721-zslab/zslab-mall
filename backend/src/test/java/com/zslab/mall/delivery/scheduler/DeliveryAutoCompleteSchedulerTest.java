package com.zslab.mall.delivery.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.delivery.adapter.DeliveryTracker;
import com.zslab.mall.delivery.adapter.DeliveryTrackingStatus;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.delivery.repository.DeliveryTrackingCandidate;
import com.zslab.mall.delivery.service.DeliveryAutoCompleteService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link DeliveryAutoCompleteScheduler} 단위 검증(Mockito·Track 99 D-210). DB·외부 호출 없이 오케스트레이션만 본다 —
 * 커서 전진(굶주림 방지)·실행 간 커서 유지(F2)·실행당 상한·조회 결과별 분기·1건 실패 격리·{@link Error} 미흡수.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryAutoCompleteSchedulerTest {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PER_RUN = 1000;

    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private DeliveryTracker deliveryTracker;
    @Mock
    private DeliveryAutoCompleteService deliveryAutoCompleteService;
    @InjectMocks
    private DeliveryAutoCompleteScheduler scheduler;

    private static DeliveryTrackingCandidate candidate(long id) {
        return new DeliveryTrackingCandidate(id, DeliveryCarrier.CJ, "TRK-" + id, LocalDateTime.now().minusDays(3));
    }

    private static List<DeliveryTrackingCandidate> page(long fromIdInclusive, int size) {
        return IntStream.range(0, size).mapToObj(offset -> candidate(fromIdInclusive + offset)).toList();
    }

    @Test
    @DisplayName("배달 완료만 전이하고 이동중·조회불가는 건너뛴다")
    void completesOnlyDeliveredCandidates() {
        when(deliveryRepository.findAutoCompleteCandidates(any(), any(), anyLong(), any()))
                .thenReturn(List.of(candidate(1L), candidate(2L), candidate(3L)))
                .thenReturn(List.of());
        when(deliveryTracker.track(any(), eq("TRK-1"), any())).thenReturn(DeliveryTrackingStatus.DELIVERED);
        when(deliveryTracker.track(any(), eq("TRK-2"), any())).thenReturn(DeliveryTrackingStatus.IN_TRANSIT);
        when(deliveryTracker.track(any(), eq("TRK-3"), any())).thenReturn(DeliveryTrackingStatus.UNKNOWN);
        when(deliveryAutoCompleteService.completeOne(eq(1L), any(), any())).thenReturn(true);

        scheduler.completeBatch();

        // 조회에 쓴 택배사·송장번호를 그대로 넘긴다(전이 직전 재확인 근거·F1).
        verify(deliveryAutoCompleteService).completeOne(1L, DeliveryCarrier.CJ, "TRK-1");
        verify(deliveryAutoCompleteService, never()).completeOne(eq(2L), any(), any());
        verify(deliveryAutoCompleteService, never()).completeOne(eq(3L), any(), any());
    }

    @Test
    @DisplayName("조회 대상은 발송(OUTBOUND)·배송중(SHIPPING)이며 커서는 훑은 마지막 id로 전진한다(미완료 건이 앞을 막지 않음)")
    void advancesCursorPastEveryScannedRow() {
        // 1페이지(가득)는 전부 이동중 — 커서가 전진하지 않으면 2페이지를 영영 못 본다.
        when(deliveryRepository.findAutoCompleteCandidates(any(), any(), anyLong(), any()))
                .thenReturn(page(1L, PAGE_SIZE))
                .thenReturn(List.of(candidate(500L)))
                .thenReturn(List.of());
        when(deliveryTracker.track(any(), any(), any())).thenReturn(DeliveryTrackingStatus.IN_TRANSIT);

        scheduler.completeBatch();

        ArgumentCaptor<Long> cursors = ArgumentCaptor.forClass(Long.class);
        verify(deliveryRepository, times(2))
                .findAutoCompleteCandidates(eq(DeliveryDirection.OUTBOUND), eq(DeliveryStatus.SHIPPING), cursors.capture(), any());
        assertThatCode(() -> {
            List<Long> captured = new ArrayList<>(cursors.getAllValues());
            org.assertj.core.api.Assertions.assertThat(captured).containsExactly(0L, (long) PAGE_SIZE);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("실행당 상한을 넘기지 않는다(남은 건은 다음 실행이 이어받는다)")
    void stopsAtMaxPerRun() {
        when(deliveryRepository.findAutoCompleteCandidates(any(), any(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    long cursor = invocation.getArgument(2);
                    return page(cursor + 1, PAGE_SIZE);
                });
        when(deliveryTracker.track(any(), any(), any())).thenReturn(DeliveryTrackingStatus.IN_TRANSIT);

        scheduler.completeBatch();

        verify(deliveryTracker, times(MAX_PER_RUN)).track(any(), any(), any());
    }

    @Test
    @DisplayName("1건 실패는 격리하고 나머지를 계속 처리한다")
    void isolatesPerItemFailure() {
        when(deliveryRepository.findAutoCompleteCandidates(any(), any(), anyLong(), any()))
                .thenReturn(List.of(candidate(1L), candidate(2L)))
                .thenReturn(List.of());
        when(deliveryTracker.track(any(), any(), any())).thenReturn(DeliveryTrackingStatus.DELIVERED);
        when(deliveryAutoCompleteService.completeOne(eq(1L), any(), any())).thenThrow(new IllegalStateException("전이 실패"));
        when(deliveryAutoCompleteService.completeOne(eq(2L), any(), any())).thenReturn(true);

        assertThatCode(() -> scheduler.completeBatch()).doesNotThrowAnyException();

        verify(deliveryAutoCompleteService).completeOne(eq(2L), any(), any());
    }

    @Test
    @DisplayName("Error는 흡수하지 않고 전파한다")
    void propagatesError() {
        when(deliveryRepository.findAutoCompleteCandidates(any(), any(), anyLong(), any()))
                .thenReturn(List.of(candidate(1L)));
        when(deliveryTracker.track(any(), any(), any())).thenThrow(new OutOfMemoryError("테스트"));

        assertThatThrownBy(() -> scheduler.completeBatch()).isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    @DisplayName("후보가 없으면 조회·전이를 호출하지 않는다")
    void noCandidates() {
        when(deliveryRepository.findAutoCompleteCandidates(any(), any(), anyLong(), any())).thenReturn(List.of());

        scheduler.completeBatch();

        verify(deliveryTracker, never()).track(any(), any(), any());
        verify(deliveryAutoCompleteService, never()).completeOne(anyLong(), any(), any());
    }

    @Test
    @DisplayName("F2 상한에서 끊기면 다음 실행이 그 커서부터 이어받는다(앞쪽만 반복 조회하지 않는다)")
    void keepsCursorAcrossRuns() {
        // 항상 가득 찬 페이지를 돌려주는 저장소 = 배송중이 상한보다 훨씬 많은 상황.
        when(deliveryRepository.findAutoCompleteCandidates(any(), any(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    long cursor = invocation.getArgument(2);
                    return page(cursor + 1, PAGE_SIZE);
                });
        when(deliveryTracker.track(any(), any(), any())).thenReturn(DeliveryTrackingStatus.IN_TRANSIT);

        scheduler.completeBatch();
        scheduler.completeBatch();

        ArgumentCaptor<Long> cursors = ArgumentCaptor.forClass(Long.class);
        verify(deliveryRepository, atLeastOnce())
                .findAutoCompleteCandidates(any(), any(), cursors.capture(), any());
        List<Long> captured = cursors.getAllValues();
        // 1회차는 0에서 시작해 MAX_PER_RUN까지 훑고, 2회차는 0이 아니라 그 지점부터 이어받는다.
        assertThat(captured.get(0)).isZero();
        assertThat(captured.get(MAX_PER_RUN / PAGE_SIZE)).isEqualTo((long) MAX_PER_RUN);
        assertThat(captured).doesNotContainSequence(0L, 0L);
    }

    @Test
    @DisplayName("F2 끝에 도달하면 커서를 0으로 되돌려 다음 실행이 처음부터 훑는다")
    void resetsCursorAtEnd() {
        // 마지막 페이지(PAGE_SIZE 미만) → 끝 도달.
        when(deliveryRepository.findAutoCompleteCandidates(any(), any(), anyLong(), any()))
                .thenReturn(List.of(candidate(1L), candidate(2L)));
        when(deliveryTracker.track(any(), any(), any())).thenReturn(DeliveryTrackingStatus.IN_TRANSIT);

        scheduler.completeBatch();
        scheduler.completeBatch();

        ArgumentCaptor<Long> cursors = ArgumentCaptor.forClass(Long.class);
        verify(deliveryRepository, times(2)).findAutoCompleteCandidates(any(), any(), cursors.capture(), any());
        assertThat(cursors.getAllValues()).containsExactly(0L, 0L);
    }

    @Test
    @DisplayName("F2 빈 페이지(커서 뒤 남은 건 없음)도 커서를 0으로 되돌린다")
    void resetsCursorWhenPageEmpty() {
        when(deliveryRepository.findAutoCompleteCandidates(any(), any(), anyLong(), any()))
                .thenReturn(page(1L, PAGE_SIZE))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(deliveryTracker.track(any(), any(), any())).thenReturn(DeliveryTrackingStatus.IN_TRANSIT);

        scheduler.completeBatch();
        scheduler.completeBatch();

        ArgumentCaptor<Long> cursors = ArgumentCaptor.forClass(Long.class);
        verify(deliveryRepository, times(3)).findAutoCompleteCandidates(any(), any(), cursors.capture(), any());
        // 1회차: 0 → PAGE_SIZE(빈 페이지 → 0 복귀) · 2회차: 0
        assertThat(cursors.getAllValues()).containsExactly(0L, (long) PAGE_SIZE, 0L);
    }
}
