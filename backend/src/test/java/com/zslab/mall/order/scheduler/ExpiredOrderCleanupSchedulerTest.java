package com.zslab.mall.order.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.order.service.ExpiredOrderCleanupService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * {@link ExpiredOrderCleanupScheduler} 단위 검증(Mockito·FE-12c-2). 배치 오케스트레이션의 부분 실패 격리
 * (RuntimeException 흡수 후 다음 건 진행)와 Error 미흡수(치명 오류 전파·후속 중단)를 커버한다
 * ({@code OrderAutoCancelSchedulerTest} 원형 복제·PAYMENT_EXPIRED·updatedAt 기준 조회 검증).
 */
@ExtendWith(MockitoExtension.class)
class ExpiredOrderCleanupSchedulerTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ExpiredOrderCleanupService expiredOrderCleanupService;
    @InjectMocks
    private ExpiredOrderCleanupScheduler scheduler;

    private Order orderWithId(long id) {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(id);
        return order;
    }

    private void givenCleanupCandidates(Order... orders) {
        when(orderRepository.findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
                eq(OrderStatus.PAYMENT_EXPIRED), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(orders));
    }

    @Test
    @DisplayName("정상: 삭제 후보 3건 → cleanupOne 3회 호출")
    void cleanupBatch_allSucceed() {
        givenCleanupCandidates(orderWithId(1L), orderWithId(2L), orderWithId(3L));

        scheduler.cleanupBatch();

        verify(expiredOrderCleanupService).cleanupOne(1L);
        verify(expiredOrderCleanupService).cleanupOne(2L);
        verify(expiredOrderCleanupService).cleanupOne(3L);
    }

    @Test
    @DisplayName("부분 실패 격리: 2번째 RuntimeException(RESTRICT 등) → 1·2·3 모두 호출·예외 미전파")
    void cleanupBatch_partialFailure_isolatesAndContinues() {
        givenCleanupCandidates(orderWithId(1L), orderWithId(2L), orderWithId(3L));
        doThrow(new RuntimeException("삭제 실패 모의")).when(expiredOrderCleanupService).cleanupOne(2L);

        assertThatCode(() -> scheduler.cleanupBatch()).doesNotThrowAnyException();

        verify(expiredOrderCleanupService).cleanupOne(1L);
        verify(expiredOrderCleanupService).cleanupOne(2L);
        verify(expiredOrderCleanupService).cleanupOne(3L);   // 2번째 실패에도 3번째 진행
    }

    @Test
    @DisplayName("Error 미흡수: 1번째 Error → cleanupBatch 전파·후속 건 미호출")
    void cleanupBatch_errorNotAbsorbed_propagates() {
        givenCleanupCandidates(orderWithId(1L), orderWithId(2L), orderWithId(3L));
        doThrow(new Error("치명 오류 모의")).when(expiredOrderCleanupService).cleanupOne(1L);

        assertThatThrownBy(() -> scheduler.cleanupBatch()).isInstanceOf(Error.class);

        verify(expiredOrderCleanupService).cleanupOne(1L);
        verify(expiredOrderCleanupService, never()).cleanupOne(2L);
        verify(expiredOrderCleanupService, never()).cleanupOne(3L);
    }

    @Test
    @DisplayName("삭제 대상 없음: 빈 배치 → cleanupOne 미호출")
    void cleanupBatch_noCandidates_noop() {
        when(orderRepository.findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
                eq(OrderStatus.PAYMENT_EXPIRED), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        scheduler.cleanupBatch();

        verify(expiredOrderCleanupService, never()).cleanupOne(anyLong());
    }
}
