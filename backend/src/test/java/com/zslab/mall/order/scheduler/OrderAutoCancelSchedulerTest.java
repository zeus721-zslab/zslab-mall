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
import com.zslab.mall.order.service.OrderAutoCancelService;
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
 * {@link OrderAutoCancelScheduler} 단위 검증(Mockito·D-153 Phase 1). 배치 오케스트레이션의 부분 실패 격리
 * (RuntimeException 흡수 후 다음 건 진행)와 Error 미흡수(치명 오류 전파·후속 중단)를 커버한다
 * ({@code ExpirePaymentSchedulerTest} 원형 복제).
 */
@ExtendWith(MockitoExtension.class)
class OrderAutoCancelSchedulerTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderAutoCancelService orderAutoCancelService;
    @InjectMocks
    private OrderAutoCancelScheduler scheduler;

    private Order orderWithId(long id) {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(id);
        return order;
    }

    private void givenAutoCancelCandidates(Order... orders) {
        when(orderRepository.findByStatusAndCreatedAtLessThanEqualOrderByCreatedAtAsc(
                eq(OrderStatus.PENDING_PAYMENT), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(orders));
    }

    @Test
    @DisplayName("정상: 자동취소 후보 3건 → cancelOne 3회 호출")
    void cancelBatch_allSucceed() {
        givenAutoCancelCandidates(orderWithId(1L), orderWithId(2L), orderWithId(3L));

        scheduler.cancelBatch();

        verify(orderAutoCancelService).cancelOne(1L);
        verify(orderAutoCancelService).cancelOne(2L);
        verify(orderAutoCancelService).cancelOne(3L);
    }

    @Test
    @DisplayName("부분 실패 격리: 2번째 RuntimeException → 1·2·3 모두 호출·예외 미전파")
    void cancelBatch_partialFailure_isolatesAndContinues() {
        givenAutoCancelCandidates(orderWithId(1L), orderWithId(2L), orderWithId(3L));
        doThrow(new RuntimeException("취소 실패 모의")).when(orderAutoCancelService).cancelOne(2L);

        assertThatCode(() -> scheduler.cancelBatch()).doesNotThrowAnyException();

        verify(orderAutoCancelService).cancelOne(1L);
        verify(orderAutoCancelService).cancelOne(2L);
        verify(orderAutoCancelService).cancelOne(3L);   // 2번째 실패에도 3번째 진행
    }

    @Test
    @DisplayName("Error 미흡수: 1번째 Error → cancelBatch 전파·후속 건 미호출")
    void cancelBatch_errorNotAbsorbed_propagates() {
        givenAutoCancelCandidates(orderWithId(1L), orderWithId(2L), orderWithId(3L));
        doThrow(new Error("치명 오류 모의")).when(orderAutoCancelService).cancelOne(1L);

        assertThatThrownBy(() -> scheduler.cancelBatch()).isInstanceOf(Error.class);

        verify(orderAutoCancelService).cancelOne(1L);
        verify(orderAutoCancelService, never()).cancelOne(2L);
        verify(orderAutoCancelService, never()).cancelOne(3L);
    }

    @Test
    @DisplayName("자동취소 대상 없음: 빈 배치 → cancelOne 미호출")
    void cancelBatch_noCandidates_noop() {
        when(orderRepository.findByStatusAndCreatedAtLessThanEqualOrderByCreatedAtAsc(
                eq(OrderStatus.PENDING_PAYMENT), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        scheduler.cancelBatch();

        verify(orderAutoCancelService, never()).cancelOne(anyLong());
    }
}
