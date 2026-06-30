package com.zslab.mall.refund.service;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.gateway.MockRefundResponse;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.repository.PaymentRepository;
import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.enums.RefundCallbackStatus;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.refund.exception.RefundIdempotentNoOpException;
import com.zslab.mall.refund.exception.RefundInvariantViolationException;
import com.zslab.mall.refund.exception.RefundNotFoundException;
import com.zslab.mall.refund.repository.RefundRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 Application Service(Track 5·expected-spec §5.1). 트랜잭션 경계는 메서드 단위다.
 *
 * <p>환불 시작(initiate)·PG 콜백 처리(handleCallback → markCompleted·markFailed)를 담당한다. 상태 전이 도메인 규칙은
 * {@link Refund}·{@link RefundStatus}가 보유한다. REST 엔드포인트로는 노출하지 않으며(initiate는 후속 승인 시스템·테스트 호출),
 * 콜백 수신만 {@code RefundWebhookController}가 위임한다.
 *
 * <p><b>이벤트(D-29·D-69)</b>: markCompleted는 도메인 메서드 → pull → save → 동기 publish 순서다(save→publish·flush 없음).
 * 소비 핸들러는 {@code @TransactionalEventListener(AFTER_COMMIT)}로 Refund 커밋 후 진입한다.
 *
 * <p><b>교차 Aggregate 해소</b>: {@link #initiate}는 Claim → OrderItem → Order → PAID Payment 그래프로 결제 행을 해소한다
 * (PAY-3a로 주문당 PAID ≤1). PAY-1 누적 검증은 initiate(사전)·markCompleted(사후·D-68 비관적 락)에서 2회 수행한다(Q5).
 */
@Slf4j
@Service
@Transactional
public class RefundService {

    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final TracedEventPublisher eventPublisher;

    public RefundService(
            ClaimRepository claimRepository,
            OrderItemRepository orderItemRepository,
            PaymentRepository paymentRepository,
            RefundRepository refundRepository,
            PaymentGateway paymentGateway,
            TracedEventPublisher eventPublisher) {
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 환불을 시작한다(expected-spec §5.1·§7). Claim.APPROVED 검증(CLM-3) → PAY-1 사전 누적 검증 → Refund(PENDING) INSERT →
     * PG 환불 요청 등록 순서다. PG 응답의 pg_refund_id를 PENDING 행에 부여해 이후 webhook 콜백 매칭 키로 쓴다.
     *
     * <p><b>PG 호출 예외(D-67·CR-03)</b>: {@code gateway.refund()}가 예외(네트워크·timeout·gateway)를 던지면 PENDING 행을
     * FAILED로 전이해 영구 PENDING 잔존을 차단한다. 별도 failure_reason 컬럼은 두지 않고 로깅만 남긴다(CR-01 보류).
     *
     * <p><b>멱등 게이트(D-94 Q6)</b>: 동일 claimId에 활성 Refund(PENDING/COMPLETED)가 이미 있으면 신규 생성 없이 기존 행을
     * no-op 반환한다(ClaimApproved 이벤트 재전달·중복 진입 차단). FAILED는 활성에서 제외해 RFN-2 재시도와 충돌하지 않는다.
     *
     * @param claimId 환불 대상 클레임 id(APPROVED여야 함·CLM-3)
     * @param amount  환불 금액(KRW 정수·1 이상)
     * @return 생성된 Refund(정상 시 PENDING+pg_refund_id, PG 예외 시 FAILED)
     * @throws ClaimNotFoundException          클레임이 없는 경우
     * @throws ClaimInvalidStateException      클레임이 APPROVED가 아닌 경우(CLM-3)
     * @throws RefundInvariantViolationException PAY-1 사전 한도 초과(과환불 차단)
     */
    public Refund initiate(Long claimId, long amount) {
        if (claimId == null) {
            throw new IllegalArgumentException("claimId는 필수입니다.");
        }
        if (amount < 1) {
            throw new IllegalArgumentException("환불금액은 1 이상이어야 합니다. 입력: " + amount);
        }

        // 멱등 게이트(D-94 Q6 α′): 동일 claimId에 활성 Refund(PENDING/COMPLETED) 존재 시 신규 생성 없이 기존 행을 no-op 반환한다.
        // 인메모리 ApplicationEvent 가정 하 이벤트 재전달·운영 재실행·중복 진입을 차단한다(외부 브로커·Outbox 도입 시 재검토·D-94 박제).
        if (refundRepository.existsActiveByClaimId(claimId)) {
            return refundRepository.findByClaimId(claimId).stream()
                    .filter(existing -> existing.getStatus() == RefundStatus.PENDING
                            || existing.getStatus() == RefundStatus.COMPLETED)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "멱등 게이트 모순: existsActive=true이나 활성 Refund 미발견 claimId=" + claimId));
        }

        // CLM-3: Claim.APPROVED 후에만 환불 생성
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
        if (claim.getStatus() != ClaimStatus.APPROVED) {
            throw new ClaimInvalidStateException(
                    "환불은 APPROVED 클레임에서만 생성할 수 있습니다(CLM-3). 현재 상태: " + claim.getStatus());
        }

        // payment 해소: claim → order_item → order → PAID payment
        Payment payment = resolvePaidPayment(claim);

        // PAY-1 사전 검증: Σ(COMPLETED) + 신규 amount ≤ Payment.amount
        long alreadyCompleted = refundRepository.sumCompletedByPaymentId(payment.getId());
        if (alreadyCompleted + amount > payment.getAmount()) {
            throw new RefundInvariantViolationException(
                    "PAY-1 위반(사전): 누적 " + alreadyCompleted + " + 신규 " + amount + " > 결제액 " + payment.getAmount());
        }

        // Refund(PENDING) INSERT (PG 호출 전·§7)
        Refund refund = Refund.create(claimId, payment.getId(), amount);
        refundRepository.save(refund);

        // PG 환불 요청 등록. 호출 예외 시 FAILED 전이(D-67).
        try {
            MockRefundResponse response = paymentGateway.refund(payment.getPgTid(), amount);
            refund.assignPgRefundId(response.pgRefundId());
        } catch (RuntimeException gatewayException) {
            log.warn("[Refund] PG 환불 요청 예외 → FAILED 전이(D-67): claimId={}, refundId={}, 원인={}",
                    claimId, refund.getId(), gatewayException.toString());
            refund.markFailed();
        }
        return refundRepository.save(refund);
    }

    /**
     * 환불 완료를 적용한다(PENDING → COMPLETED·expected-spec §5.1). pg_refund_id로 행을 찾고 RFN-1·RFN-3·PAY-1 사후를 가드한 뒤
     * refunded_at을 시스템 시각(D-70)으로 채우고 {@code RefundCompleted}를 발행한다(save→publish·D-29).
     *
     * @param pgRefundId webhook 콜백이 운반한 PG 환불 식별자(매칭 키·RFN-1 필수)
     * @return COMPLETED로 전이된 Refund
     * @throws RefundInvariantViolationException pg_refund_id 누락(RFN-1)·PAY-1 사후 한도 초과
     * @throws RefundNotFoundException            pg_refund_id 미매칭
     * @throws RefundIdempotentNoOpException      이미 COMPLETED인 환불 재호출(RFN-3 멱등 시그널)
     */
    public Refund markCompleted(String pgRefundId) {
        // RFN-1: pg_refund_id 없이 COMPLETED 전이 불가
        if (pgRefundId == null || pgRefundId.isBlank()) {
            throw new RefundInvariantViolationException("RFN-1 위반: pg_refund_id 없이 COMPLETED 전이 불가.");
        }
        Refund refund = refundRepository.findByPgRefundId(pgRefundId)
                .orElseThrow(() -> new RefundNotFoundException("환불 행을 찾을 수 없습니다: pgRefundId=" + pgRefundId));

        // RFN-3: 이미 COMPLETED면 멱등 no-op 시그널(직접 호출 경로·webhook은 handleCallback에서 선검사)
        if (refund.getStatus() == RefundStatus.COMPLETED) {
            throw new RefundIdempotentNoOpException("이미 완료된 환불 콜백입니다(RFN-3): pgRefundId=" + pgRefundId);
        }

        // PAY-1 사후 재검증(D-68): Payment 행 비관적 락으로 동시 환불 직렬화 후 누적 한도 재확인
        Payment payment = paymentRepository.findByIdForUpdate(refund.getPaymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "환불 대상 결제를 찾을 수 없습니다: paymentId=" + refund.getPaymentId()));
        long alreadyCompleted = refundRepository.sumCompletedByPaymentId(refund.getPaymentId());
        if (alreadyCompleted + refund.getAmount() > payment.getAmount()) {
            throw new RefundInvariantViolationException(
                    "PAY-1 위반(사후): 누적 " + alreadyCompleted + " + 신규 " + refund.getAmount()
                            + " > 결제액 " + payment.getAmount());
        }

        // 전이 + refunded_at 시스템 시각(D-70) + 이벤트 누적
        refund.markCompleted(LocalDateTime.now());
        List<Object> events = refund.pullDomainEvents();
        refundRepository.save(refund);
        events.forEach(eventPublisher::publishEvent); // D-29 save→publish
        return refund;
    }

    /**
     * 환불 실패를 적용한다(PENDING → FAILED·expected-spec §5.1). 이미 FAILED면 멱등 no-op으로 반환한다.
     *
     * @param refundId 환불 행 id
     * @param reason   실패 사유(로깅 전용·failure_reason 컬럼 없음·CR-01 보류)
     * @return FAILED로 전이된(또는 이미 FAILED인) Refund
     * @throws RefundNotFoundException refundId 미매칭
     */
    public Refund markFailed(Long refundId, String reason) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new RefundNotFoundException("환불 행을 찾을 수 없습니다: refundId=" + refundId));
        if (refund.getStatus() == RefundStatus.FAILED) {
            log.info("[Refund] FAIL 멱등 NO-OP(이미 FAILED): refundId={}", refundId);
            return refund;
        }
        refund.markFailed();
        log.warn("[Refund] 환불 실패 처리(FAILED): refundId={}, reason={}", refundId, reason);
        return refundRepository.save(refund);
    }

    /**
     * webhook 콜백을 처리한다(expected-spec §6). pg_refund_id로 행을 찾고 status로 분기한다. 종결 상태 재수신은 멱등 no-op이다
     * (RFN-3·예외 없이 200 응답 유도). 정상 분기는 {@link #markCompleted}·{@link #markFailed}에 위임한다.
     *
     * @throws RefundNotFoundException pg_refund_id 미매칭
     */
    public void handleCallback(String pgRefundId, RefundCallbackStatus status, String failureReason) {
        if (pgRefundId == null || pgRefundId.isBlank() || status == null) {
            throw new IllegalArgumentException("환불 콜백 입력 누락(pgRefundId·status).");
        }
        Refund refund = refundRepository.findByPgRefundId(pgRefundId)
                .orElseThrow(() -> new RefundNotFoundException("환불 행을 찾을 수 없습니다: pgRefundId=" + pgRefundId));

        switch (status) {
            case SUCCESS -> {
                if (refund.getStatus() == RefundStatus.COMPLETED) {
                    log.info("[Refund] SUCCESS 콜백 멱등 NO-OP(이미 COMPLETED·RFN-3): pgRefundId={}", pgRefundId);
                    return;
                }
                markCompleted(pgRefundId);
            }
            case FAIL -> {
                if (refund.getStatus() == RefundStatus.FAILED) {
                    log.info("[Refund] FAIL 콜백 멱등 NO-OP(이미 FAILED): pgRefundId={}", pgRefundId);
                    return;
                }
                markFailed(refund.getId(), failureReason);
            }
        }
    }

    /** 운영 조회: 한 클레임의 전체 환불 행(재시도 = 새 행·RFN-2 추적). */
    @Transactional(readOnly = true)
    public List<Refund> findByClaimId(Long claimId) {
        return refundRepository.findByClaimId(claimId);
    }

    /** claim → order_item → order → PAID payment 해소(PAY-3a로 주문당 PAID ≤1). */
    private Payment resolvePaidPayment(Claim claim) {
        Long orderId = orderItemRepository.findOrderIdById(claim.getOrderItemId())
                .orElseThrow(() -> new IllegalStateException(
                        "주문 품목을 찾을 수 없습니다: orderItemId=" + claim.getOrderItemId()));
        return paymentRepository.findFirstByOrderIdAndStatusOrderByIdDesc(orderId, PaymentStatus.PAID)
                .orElseThrow(() -> new IllegalStateException("환불 대상 PAID 결제를 찾을 수 없습니다: orderId=" + orderId));
    }
}
