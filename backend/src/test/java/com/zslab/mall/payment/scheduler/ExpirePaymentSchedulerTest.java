package com.zslab.mall.payment.scheduler;

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

import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.repository.PaymentRepository;
import com.zslab.mall.payment.service.ExpirePaymentService;
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
 * {@link ExpirePaymentScheduler} 단위 검증(Mockito). 배치 오케스트레이션의 부분 실패 격리(RuntimeException 흡수 후
 * 다음 건 진행)와 Error 미흡수(치명 오류 전파·후속 중단)를 커버한다.
 */
@ExtendWith(MockitoExtension.class)
class ExpirePaymentSchedulerTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private ExpirePaymentService expirePaymentService;
    @InjectMocks
    private ExpirePaymentScheduler scheduler;

    private Payment paymentWithId(long id) {
        Payment payment = mock(Payment.class);
        when(payment.getId()).thenReturn(id);
        return payment;
    }

    private void givenExpiredCandidates(Payment... payments) {
        when(paymentRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                eq(PaymentStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(payments));
    }

    @Test
    @DisplayName("정상: 만료 후보 3건 → expireOne 3회 호출")
    void expireBatch_allSucceed() {
        givenExpiredCandidates(paymentWithId(1L), paymentWithId(2L), paymentWithId(3L));

        scheduler.expireBatch();

        verify(expirePaymentService).expireOne(1L);
        verify(expirePaymentService).expireOne(2L);
        verify(expirePaymentService).expireOne(3L);
    }

    @Test
    @DisplayName("부분 실패 격리: 2번째 RuntimeException → 1·2·3 모두 호출·예외 미전파")
    void expireBatch_partialFailure_isolatesAndContinues() {
        givenExpiredCandidates(paymentWithId(1L), paymentWithId(2L), paymentWithId(3L));
        doThrow(new RuntimeException("전이 실패 모의")).when(expirePaymentService).expireOne(2L);

        assertThatCode(() -> scheduler.expireBatch()).doesNotThrowAnyException();

        verify(expirePaymentService).expireOne(1L);
        verify(expirePaymentService).expireOne(2L);
        verify(expirePaymentService).expireOne(3L);   // 2번째 실패에도 3번째 진행
    }

    @Test
    @DisplayName("Error 미흡수: 1번째 Error → expireBatch 전파·후속 건 미호출")
    void expireBatch_errorNotAbsorbed_propagates() {
        givenExpiredCandidates(paymentWithId(1L), paymentWithId(2L), paymentWithId(3L));
        doThrow(new Error("치명 오류 모의")).when(expirePaymentService).expireOne(1L);

        assertThatThrownBy(() -> scheduler.expireBatch()).isInstanceOf(Error.class);

        verify(expirePaymentService).expireOne(1L);
        verify(expirePaymentService, never()).expireOne(2L);
        verify(expirePaymentService, never()).expireOne(3L);
    }

    @Test
    @DisplayName("만료 대상 없음: 빈 배치 → expireOne 미호출")
    void expireBatch_noCandidates_noop() {
        when(paymentRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                eq(PaymentStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        scheduler.expireBatch();

        verify(expirePaymentService, never()).expireOne(anyLong());
    }
}
