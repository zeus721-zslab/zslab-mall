package com.zslab.mall.payment.service;

import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.common.util.PublicIdGenerator;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.order.service.OrderAutoCancelService;
import com.zslab.mall.payment.command.PaymentCallbackCommand;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.exception.InvalidCallbackException;
import com.zslab.mall.payment.exception.PaymentAlreadyCompletedException;
import com.zslab.mall.payment.exception.PaymentInProgressException;
import com.zslab.mall.payment.exception.PaymentNotFoundException;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.repository.PaymentRepository;
import com.zslab.mall.refund.repository.RefundRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 Application Service(D-27·D-36). 트랜잭션 경계는 메서드 단위다.
 *
 * <p>결제 시도 생성(initiate)·PG 콜백 처리(handleCallback)·조회를 담당한다. 상태 전이 도메인 규칙은
 * {@link Payment}·{@link PaymentStatus}가 보유하며 별도 Domain Service를 두지 않는다(D-36).
 *
 * <p><b>이벤트 발행(D-29)</b>: 도메인 메서드 호출 → {@link Payment#pullDomainEvents} → save(flush) → 동기 발행 순서다.
 * 발행은 {@link ApplicationEventPublisher} 동기이며 소비 핸들러와 동일 트랜잭션·한쪽 실패 시 전체 롤백된다.
 * Outbox·{@code @TransactionalEventListener(AFTER_COMMIT)}는 현 단계 미도입(향후 IntegrationEvent 전환 예정).
 *
 * <p><b>콜백 예외 정책</b>: handleCallback은 REJECT·행 미발견·PAY-3a 위반 anomaly를 {@link InvalidCallbackException}으로
 * 통일한다(Controller가 HTTP 422 매핑·D-34). initiate 경로 예외({@link PaymentAlreadyCompletedException}·
 * {@link PaymentInProgressException})는 결제 시도 호출자(체크아웃 흐름)가 처리한다.
 */
@Slf4j
@Service
@Transactional
public class PaymentService {

    /** PENDING 결제 기본 TTL(D-32). */
    private static final Duration PENDING_TTL = Duration.ofMinutes(30);

    /** payment_attempt_key prefix(D-35). */
    private static final String ATTEMPT_KEY_PREFIX = "pat";

    /** FAILURE 콜백에 failureCode가 없을 때의 기본값(PG 실제 결제 실패). */
    private static final String DEFAULT_FAILURE_CODE = "PG_FAILURE";

    /** FAILURE 콜백 metadata에서 failureCode를 꺼낼 키. */
    private static final String METADATA_FAILURE_CODE_KEY = "failureCode";

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final TracedEventPublisher eventPublisher;
    private final RefundRepository refundRepository;
    private final OrderAutoCancelService orderAutoCancelService;

    public PaymentService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway,
            TracedEventPublisher eventPublisher,
            RefundRepository refundRepository,
            OrderAutoCancelService orderAutoCancelService) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
        this.refundRepository = refundRepository;
        this.orderAutoCancelService = orderAutoCancelService;
    }

    /**
     * 결제 시도를 생성한다(D-28·D-56). 항상 새 PENDING 행을 만들며 기존 행을 재사용하지 않는다.
     *
     * <p>본인 일치 검증(§2)과 amount 서버 재계산(D-61)만 수행하는 순수 결제 생성 책임이다. 상품 상태·재고 재검증(D-60)은
     * 재결제 경로의 {@code CheckoutService}가 본 메서드 호출 전에 담당한다(D-63·신규 주문 경로는 재검증 미적용).
     *
     * @param orderPublicId 결제 대상 주문 public_id(ord_)
     * @param buyerId 요청자 buyer_id(X-Buyer-Id·D-39). Order.buyer_id와 일치해야 한다
     * @param method 결제 수단
     * @return 결제 시도 결과(PENDING 결제 행 + PG 발급 redirectUrl)
     * @throws IllegalArgumentException 입력이 불완전한 경우
     * @throws OrderNotFoundException 주문이 없거나 타인 주문인 경우(404·정보 노출 회피·§2)
     * @throws PaymentAlreadyCompletedException 한 주문에 이미 PAID 행이 있는 경우(PAY-3a)
     * @throws PaymentInProgressException 미만료 PENDING 행이 존재해 새 시도가 차단되는 경우(D-32)
     */
    public PaymentInitiation initiate(String orderPublicId, Long buyerId, PaymentMethod method) {
        if (orderPublicId == null || orderPublicId.isBlank() || buyerId == null || method == null) {
            throw new IllegalArgumentException("결제 시도 입력 누락(orderPublicId·buyerId·method).");
        }

        // §2·D-42: 주문 조회 + 본인 일치 검증. 미존재·타인 주문 모두 404로 통일(정보 노출 회피).
        Order order = orderRepository.findByPublicId(orderPublicId)
                .orElseThrow(() -> new OrderNotFoundException("주문을 찾을 수 없습니다: " + orderPublicId));
        if (!order.getBuyerId().equals(buyerId)) {
            throw new OrderNotFoundException("주문을 찾을 수 없습니다: " + orderPublicId);
        }
        Long orderId = order.getId();

        // PAY-3a: 이미 결제 완료된 주문은 추가 시도 차단(MariaDB partial index 미지원·Service 단독 가드·D-31)
        if (paymentRepository.existsByOrderIdAndStatus(orderId, PaymentStatus.PAID)) {
            throw new PaymentAlreadyCompletedException("이미 결제 완료된 주문입니다: orderId=" + orderId);
        }

        // D-32: 미만료 PENDING 존재 시 중복 시도 차단. 만료 PENDING은 무시하고 새 시도 허용.
        LocalDateTime now = LocalDateTime.now();
        Optional<Payment> latestPending =
                paymentRepository.findFirstByOrderIdAndStatusOrderByIdDesc(orderId, PaymentStatus.PENDING);
        if (latestPending.isPresent() && !latestPending.get().isExpired(now)) {
            throw new PaymentInProgressException("진행 중인 결제가 있습니다: orderId=" + orderId);
        }

        // D-56·D-61: amount는 Order에서 서버 재계산(클라이언트 신뢰 차단). Track 4 시점 discount·shipping=0.
        long amount = recomputeAmount(order);

        String attemptKey = PublicIdGenerator.generate(ATTEMPT_KEY_PREFIX);
        Payment payment = Payment.create(orderId, method, amount, attemptKey, now.plus(PENDING_TTL));
        Payment saved = paymentRepository.save(payment);

        // PG에 결제 시도 등록(Mock). 반환 결제창 URL(redirectUrl)은 CheckoutResponse(§7)로 전달한다.
        String redirectUrl = paymentGateway.requestPayment(attemptKey, saved.getAmount(), saved.getMethod());

        return new PaymentInitiation(saved, redirectUrl);
    }

    /** 실 결제액을 Order에서 재계산한다(D-61): total_price − discount_amount + shipping_fee. */
    private long recomputeAmount(Order order) {
        return order.getTotalPrice() - order.getDiscountAmount() + order.getShippingFee();
    }

    /**
     * PG 콜백을 처리한다(D-34 매트릭스). 결제 행은 paymentAttemptKey로 식별한다(D-35).
     *
     * @throws InvalidCallbackException 행 미발견·REJECT 조합·PAY-3a 위반 anomaly(Controller가 HTTP 422로 응답)
     */
    public void handleCallback(PaymentCallbackCommand command) {
        if (command == null || command.callbackType() == null
                || command.paymentAttemptKey() == null || command.occurredAt() == null) {
            throw new IllegalArgumentException("콜백 입력 누락(callbackType·paymentAttemptKey·occurredAt).");
        }

        Payment payment = paymentRepository.findByPaymentAttemptKey(command.paymentAttemptKey())
                .orElseThrow(() -> new InvalidCallbackException(
                        "결제 행을 찾을 수 없습니다: attemptKey=" + command.paymentAttemptKey()));

        switch (command.callbackType()) {
            case SUCCESS -> handleSuccess(payment, command);
            case FAILURE -> handleFailure(payment, command);
            case CANCEL -> handleCancel(payment, command);
        }

        // D-29: pull → save(flush) → 동기 발행. 상태 무변경(NO-OP) 시 events 비어 있고 save는 dirty 없음.
        List<Object> events = payment.pullDomainEvents();
        paymentRepository.save(payment);
        events.forEach(eventPublisher::publishEvent);
    }

    /** 운영 조회: 한 주문의 전체 결제 행(최신순·D-32 운영 화면). */
    @Transactional(readOnly = true)
    public List<Payment> findAllByOrderId(Long orderId) {
        return paymentRepository.findAllByOrderIdOrderByIdDesc(orderId);
    }

    /** 사용자 조회: 한 주문의 PAID 결제 행(결제 화면·PAY-3a로 ≤1건). */
    @Transactional(readOnly = true)
    public Optional<Payment> findPaidByOrderId(Long orderId) {
        return paymentRepository.findFirstByOrderIdAndStatusOrderByIdDesc(orderId, PaymentStatus.PAID);
    }

    /**
     * 환불 완료에 따라 결제를 취소 상태로 전이한다(PAID → CANCELLED·Track 5·PAY-2·D-71). {@code RefundCompleted} 이벤트 핸들러가
     * 호출한다(AFTER_COMMIT·각자 별도 트랜잭션).
     *
     * <p><b>전이 조건(D-71)</b>: Track 5 범위에서 CANCELLED는 <b>전액 환불 완료</b>를 의미한다. Σ(Refund.COMPLETED.amount) ==
     * Payment.amount일 때만 전이하며, 부분환불(Σ &lt; amount)은 상태를 유지(no-op)한다. 이미 CANCELLED면 멱등 no-op이다.
     *
     * <p>Track 28 D-113의 {@link #markCancelledByAdmin}(운영자 수동 보정)도 본 메서드에 위임하며 전액 환불 가드·멱등 NO-OP를 그대로 공유한다.
     *
     * @param paymentId 결제 행 id
     * @throws PaymentNotFoundException 결제가 없는 경우(404)
     */
    public void markCancelled(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("결제를 찾을 수 없습니다: paymentId=" + paymentId));
        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            log.info("[Payment] markCancelled 멱등 NO-OP(이미 CANCELLED): paymentId={}", paymentId);
            return;
        }
        long totalRefunded = refundRepository.sumCompletedByPaymentId(paymentId);
        if (totalRefunded != payment.getAmount()) {
            // D-71: 전액 환불 일치 시에만 CANCELLED. 부분환불은 상태 유지(후속 트랙에서 의미 재정의 가능).
            log.info("[Payment] markCancelled NO-OP(부분환불·D-71): paymentId={}, 누적환불={}, 결제액={}",
                    paymentId, totalRefunded, payment.getAmount());
            return;
        }
        payment.cancel(); // PAID → CANCELLED (PAY-2·canTransitionTo 강제)
        paymentRepository.save(payment);
    }

    /**
     * 운영자 수동 결제 취소(Track 28 D-113·admin actor wrapper). primitive {@link #markCancelled}에 위임하며 actor 파라미터는
     * 수신하지 않는다(D-92 원칙·markCancelled가 이미 actor 비의존 시그니처). 전액 환불 가드(D-71)·CANCELLED 멱등 NO-OP를
     * 그대로 상속한다(강제 취소 아님·부분·미환불이면 상태 유지).
     *
     * <p><b>용도</b>: {@code RefundCompleted → Payment CANCELLED} 자동 전이({@code PaymentRefundCompletedHandler})가 유실돼
     * 전액 환불 완료 결제가 PAID로 잔존할 때, 운영자가 동일 결제를 수동으로 CANCELLED 보정하는 fallback 경로다.
     *
     * <p><b>반환</b>: {@link #markCancelled}가 로드한 관리 엔티티를 동일 트랜잭션·영속성 컨텍스트(self-invocation·전파 없음)에서
     * 재취득한다. {@code findById} 재호출은 1차 캐시 히트로 DB 재조회를 유발하지 않으며 {@code cancel()} 상태 변경이 반영된
     * 동일 인스턴스를 돌려준다(NO-OP 경로도 현재 상태 반영). 미존재는 {@link #markCancelled}가 선차단하므로 여기 orElseThrow는 도달 불가 방어선이다.
     *
     * @param paymentId 결제 행 id
     * @return 취소 반영(또는 NO-OP 후 현재 상태) 결제 행. Controller는 스칼라 status만 읽는다
     * @throws PaymentNotFoundException 결제가 없는 경우(404)
     */
    public Payment markCancelledByAdmin(Long paymentId) {
        markCancelled(paymentId);
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("결제를 찾을 수 없습니다: paymentId=" + paymentId));
    }

    /** SUCCESS 콜백 처리(D-34): PENDING→PAID / PAID 멱등 NO-OP / FAILED·CANCELLED REJECT. */
    private void handleSuccess(Payment payment, PaymentCallbackCommand command) {
        switch (payment.getStatus()) {
            case PENDING -> {
                // PAY-3a 재검증: 동일 주문에 PAID 행이 이미 있으면 성립 불가(만료 PENDING 지연 콜백 anomaly·D-32 운영 알림)
                if (paymentRepository.existsByOrderIdAndStatus(payment.getOrderId(), PaymentStatus.PAID)) {
                    log.warn("[Payment] SUCCESS 콜백 PAY-3a 위반: orderId={}, attemptKey={} — 이미 PAID 행 존재",
                            payment.getOrderId(), command.paymentAttemptKey());
                    throw new InvalidCallbackException(
                            "이미 결제 완료된 주문의 지연 SUCCESS 콜백입니다: orderId=" + payment.getOrderId());
                }
                payment.complete(command.occurredAt(), command.provider(), command.pgTid());
            }
            case PAID -> log.info("[Payment] SUCCESS 콜백 멱등 NO-OP(이미 PAID): attemptKey={}", command.paymentAttemptKey());
            case FAILED, CANCELLED, EXPIRED -> {
                log.warn("[Payment] SUCCESS 콜백 REJECT: 상태={}, attemptKey={}", payment.getStatus(), command.paymentAttemptKey());
                throw new InvalidCallbackException(
                        "종결 상태에서 SUCCESS 전이는 불가합니다: status=" + payment.getStatus());
            }
        }
    }

    /**
     * FAILURE 콜백 처리(D-34·FE-12c 정정): PENDING→PG 실제 결제 실패(Payment FAILED·failure_code 유지) + Order를
     * PAYMENT_EXPIRED 종료(cancelOne·OrderTerminated 발행) / 종결 상태 NO-OP. FAILED(실제 실패)는 EXPIRED(결제창 이탈·만료)와
     * 구분되나, Order는 셋 다 동일하게 PAYMENT_EXPIRED로 종료된다.
     */
    private void handleFailure(Payment payment, PaymentCallbackCommand command) {
        if (payment.getStatus() == PaymentStatus.PENDING) {
            payment.fail(resolveFailureCode(command), command.occurredAt());   // PENDING → FAILED (PaymentFailed 미발행)
            orderAutoCancelService.cancelOne(payment.getOrderId());            // Order PAYMENT_EXPIRED + OrderTerminated 발행
        } else {
            log.info("[Payment] FAILURE 콜백 NO-OP: 상태={}, attemptKey={}", payment.getStatus(), command.paymentAttemptKey());
        }
    }

    /**
     * CANCEL 콜백 처리(D-34·FE-12c): PAID→CANCELLED(환불 흐름) / PENDING→미결제 종료(결제창 취소·Payment EXPIRED +
     * Order PAYMENT_EXPIRED) / 종결 상태(FAILED·CANCELLED·EXPIRED) NO-OP.
     */
    private void handleCancel(Payment payment, PaymentCallbackCommand command) {
        switch (payment.getStatus()) {
            case PAID -> payment.cancel();
            case PENDING -> terminateUnpaid(payment);
            case FAILED, CANCELLED, EXPIRED ->
                    log.info("[Payment] CANCEL 콜백 NO-OP: 상태={}, attemptKey={}", payment.getStatus(), command.paymentAttemptKey());
        }
    }

    /**
     * 미결제 결제를 종료한다(FE-12c 공통 실행체). Payment를 EXPIRED로 종료하고(결제 생명주기 종료·PaymentFailed 미발행)
     * 주문을 {@link OrderAutoCancelService#cancelOne}으로 PAYMENT_EXPIRED 종료·OrderTerminated 발행에 위임한다
     * (원칙 3·재고 해제는 OrderTerminated 단일 수렴). cancelOne은 status!=PENDING_PAYMENT면 멱등 skip한다.
     */
    private void terminateUnpaid(Payment payment) {
        payment.expire();
        orderAutoCancelService.cancelOne(payment.getOrderId());
    }

    /** FAILURE 콜백 metadata에서 failureCode를 꺼낸다. 없으면 기본값(PG_FAILURE). */
    private String resolveFailureCode(PaymentCallbackCommand command) {
        Map<String, String> metadata = command.metadata();
        if (metadata == null) {
            return DEFAULT_FAILURE_CODE;
        }
        return metadata.getOrDefault(METADATA_FAILURE_CODE_KEY, DEFAULT_FAILURE_CODE);
    }
}
