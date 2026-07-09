package com.zslab.mall.payment.service;

import com.zslab.mall.order.service.OrderAutoCancelService;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PENDING 결제 만료 종료 Application Service(Track 25·D-08 M-14·FE-12c 재배선). 만료된 PENDING 결제 1건을 EXPIRED로 종료하고
 * 주문을 PAYMENT_EXPIRED로 종료(OrderTerminated 발행)한다.
 *
 * <p><b>단건 트랜잭션 경계</b>: {@link #expireOne}은 결제 1건당 독립 {@code @Transactional}이다. 배치 오케스트레이션
 * ({@link com.zslab.mall.payment.scheduler.ExpirePaymentScheduler})은 트랜잭션 없이 id별로 본 메서드를 호출하므로,
 * 한 건 실패가 다른 건의 커밋을 롤백하지 않는다(부분 실패 격리).
 *
 * <p><b>Order 종료 위임(FE-12c)</b>: Payment를 EXPIRED로 종료한 뒤 {@link OrderAutoCancelService#cancelOne}으로 주문을
 * PAYMENT_EXPIRED 종료하며, 재고 예약 해제는 cancelOne이 발행하는 OrderTerminated를 {@code InventoryOrderTerminatedHandler}
 * (AFTER_COMMIT + REQUIRES_NEW)가 본 트랜잭션 커밋 후 별도 트랜잭션에서 수행한다(원칙 3·PaymentFailed 결합 제거). 따라서 만료
 * 종료는 반드시 커밋돼야 재고가 해제된다. Payment는 도메인 이벤트를 발행하지 않는다(원칙 4·expire()).
 *
 * <p><b>멱등·다중 인스턴스 방어</b>: {@link PaymentRepository#findByIdForUpdate} 비관적 락으로 행을 잠근 뒤 상태를
 * 재검증한다. 조회~잠금 사이에 콜백으로 PAID/EXPIRED 전이된 경우 {@code status != PENDING} skip, 만료 조건 미충족 시
 * {@code !isExpired} skip이다. FOR UPDATE + 상태 재검증이 ShedLock 없이 중복 만료 시도를 자연 직렬화한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpirePaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderAutoCancelService orderAutoCancelService;

    /**
     * 만료된 PENDING 결제 1건을 EXPIRED로 종료하고 주문을 PAYMENT_EXPIRED로 종료한다. 상태·만료 조건을 잠금 후 재검증하며,
     * 조건 미충족 시 멱등 no-op이다.
     *
     * @param paymentId 만료 대상 결제 행 id
     */
    @Transactional
    public void expireOne(Long paymentId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null) {
            // 배치 조회~잠금 사이 행이 사라지는 경우는 정상 흐름상 없으나 방어적으로 skip한다.
            log.info("[PaymentExpiry] expireOne skip: 결제 행 없음 paymentId={}", paymentId);
            return;
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            // 조회~잠금 사이 SUCCESS/CANCEL 콜백으로 종결 상태 전이됨(재검증 멱등).
            log.info("[PaymentExpiry] expireOne skip: 이미 종결 status={} paymentId={}", payment.getStatus(), paymentId);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!payment.isExpired(now)) {
            log.info("[PaymentExpiry] expireOne skip: 미만료 expiresAt={} paymentId={}", payment.getExpiresAt(), paymentId);
            return;
        }

        payment.expire();                 // PENDING → EXPIRED (PaymentFailed 미발행)
        paymentRepository.save(payment);
        orderAutoCancelService.cancelOne(payment.getOrderId());   // Order PAYMENT_EXPIRED + OrderTerminated 발행

        log.info("[PaymentExpiry] expireOne EXPIRED 종료 완료 paymentId={} orderId={}", paymentId, payment.getOrderId());
    }
}
