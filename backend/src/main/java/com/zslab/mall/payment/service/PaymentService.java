package com.zslab.mall.payment.service;

import com.zslab.mall.common.util.PublicIdGenerator;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.payment.command.PaymentCallbackCommand;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.exception.InvalidCallbackException;
import com.zslab.mall.payment.exception.PaymentAlreadyCompletedException;
import com.zslab.mall.payment.exception.PaymentInProgressException;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.repository.PaymentRepository;
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

    /** FAILURE 콜백에 failureCode가 없을 때의 기본값. */
    private static final String DEFAULT_FAILURE_CODE = "PG_FAILURE";

    /** CANCEL × PENDING(결제 미완료 취소) 실패 코드(D-34). */
    private static final String CANCEL_BEFORE_PAYMENT_CODE = "CANCELLED_BEFORE_PAYMENT";

    /** FAILURE 콜백 metadata에서 failureCode를 꺼낼 키. */
    private static final String METADATA_FAILURE_CODE_KEY = "failureCode";

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway,
            ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
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
            case FAILED, CANCELLED -> {
                log.warn("[Payment] SUCCESS 콜백 REJECT: 상태={}, attemptKey={}", payment.getStatus(), command.paymentAttemptKey());
                throw new InvalidCallbackException(
                        "종결 상태에서 SUCCESS 전이는 불가합니다: status=" + payment.getStatus());
            }
        }
    }

    /** FAILURE 콜백 처리(D-34): PENDING→FAILED / PAID·FAILED·CANCELLED NO-OP. */
    private void handleFailure(Payment payment, PaymentCallbackCommand command) {
        if (payment.getStatus() == PaymentStatus.PENDING) {
            payment.fail(resolveFailureCode(command), command.occurredAt());
        } else {
            log.info("[Payment] FAILURE 콜백 NO-OP: 상태={}, attemptKey={}", payment.getStatus(), command.paymentAttemptKey());
        }
    }

    /** CANCEL 콜백 처리(D-34): PAID→CANCELLED / PENDING→FAILED(미완료 취소) / FAILED·CANCELLED NO-OP. */
    private void handleCancel(Payment payment, PaymentCallbackCommand command) {
        switch (payment.getStatus()) {
            case PAID -> payment.cancel();
            case PENDING -> payment.fail(CANCEL_BEFORE_PAYMENT_CODE, command.occurredAt());
            case FAILED, CANCELLED ->
                    log.info("[Payment] CANCEL 콜백 NO-OP: 상태={}, attemptKey={}", payment.getStatus(), command.paymentAttemptKey());
        }
    }

    /** FAILURE 콜백 metadata에서 failureCode를 꺼낸다. 없으면 기본값. */
    private String resolveFailureCode(PaymentCallbackCommand command) {
        Map<String, String> metadata = command.metadata();
        if (metadata == null) {
            return DEFAULT_FAILURE_CODE;
        }
        return metadata.getOrDefault(METADATA_FAILURE_CODE_KEY, DEFAULT_FAILURE_CODE);
    }
}
