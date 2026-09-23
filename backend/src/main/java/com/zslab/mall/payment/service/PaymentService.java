package com.zslab.mall.payment.service;

import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.common.util.PublicIdGenerator;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.order.service.OrderAutoCancelService;
import com.zslab.mall.order.service.OrderService;
import com.zslab.mall.payment.command.PaymentCallbackCommand;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.exception.InvalidCallbackException;
import com.zslab.mall.payment.exception.OrderNotPendingPaymentException;
import com.zslab.mall.payment.exception.PaymentAlreadyCompletedException;
import com.zslab.mall.payment.exception.PaymentInProgressException;
import com.zslab.mall.payment.exception.PaymentInvalidStateException;
import com.zslab.mall.payment.exception.PaymentNotFoundException;
import com.zslab.mall.payment.exception.PaymentPgTidConflictException;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.repository.PaymentRepository;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueType;
import com.zslab.mall.reconciliation.service.ReconciliationIssueRecorder;
import com.zslab.mall.reconciliation.service.ReconciliationIssueRefs;
import com.zslab.mall.refund.repository.RefundRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
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
 * <p><b>콜백 충돌 정책(Track 104-2 D-216·P1)</b>: handleCallback은 PG 사실이 내부 규칙과 충돌하면 거부 대신 불일치를 기록하고 그 유형을
 * 돌려준다(웹훅 200·mock 콜백은 호출부가 기존 4xx로 알림). initiate 경로 예외({@link PaymentAlreadyCompletedException}·
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

    /** (pg_provider, pg_tid) 유니크 제약명(V3·PAY-3b·D-31). flush 예외 메시지에서 이 제약만 409로 판별한다. */
    private static final String PG_TID_CONSTRAINT = "uk_payment_provider_pg_tid";

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final TracedEventPublisher eventPublisher;
    private final RefundRepository refundRepository;
    private final OrderAutoCancelService orderAutoCancelService;
    private final EntityManager entityManager;
    private final OrderService orderService;
    private final ReconciliationIssueRecorder reconciliationIssueRecorder;

    public PaymentService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway,
            TracedEventPublisher eventPublisher,
            RefundRepository refundRepository,
            OrderAutoCancelService orderAutoCancelService,
            EntityManager entityManager,
            OrderService orderService,
            ReconciliationIssueRecorder reconciliationIssueRecorder) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
        this.refundRepository = refundRepository;
        this.orderAutoCancelService = orderAutoCancelService;
        this.entityManager = entityManager;
        this.orderService = orderService;
        this.reconciliationIssueRecorder = reconciliationIssueRecorder;
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
        // Track 104-1 D-215(P5): 주문 쓰기 락이 첫 DB 접근이다 — 주문 엔티티는 락 뒤에 적재한다.
        orderRepository.findIdByPublicId(orderPublicId).ifPresent(orderService::lockForWrite);

        // §2·D-42: 주문 조회 + 본인 일치 검증. 미존재·타인 주문 모두 404로 통일(정보 노출 회피).
        Order order = orderRepository.findByPublicId(orderPublicId)
                .orElseThrow(() -> new OrderNotFoundException("주문을 찾을 수 없습니다: " + orderPublicId));
        if (!order.getBuyerId().equals(buyerId)) {
            throw new OrderNotFoundException("주문을 찾을 수 없습니다: " + orderPublicId);
        }

        // FE-12c-2 근본 가드: 비-PENDING_PAYMENT(미결제 종료 PAYMENT_EXPIRED·완료 등) 주문의 결제 시작 차단.
        // 삭제 대상(PAYMENT_EXPIRED) 주문에 새 PENDING payment 자식 행이 생기는 동시성 창을 닫는다. INITIATE_FAILED로
        // PENDING_PAYMENT가 유지된 주문의 재결제(D-32 만료 PENDING 새 시도 포함)는 통과한다.
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new OrderNotPendingPaymentException(
                    "결제를 시작할 수 없는 주문 상태입니다(PENDING_PAYMENT 아님): status=" + order.getStatus());
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
     * <p><b>행 락 순서(D-173 → Track 104-1 D-215)</b>: 주문 쓰기 락({@link OrderService#lockForWrite}) → Payment
     * {@code refresh(PESSIMISTIC_WRITE)} → (SUCCESS) Order {@code refresh}(이미 쥔 락·최신 재적재) → 동기 핸들러(Order 전이 → Inventory
     * FOR UPDATE) 순으로 잡는다. 결제 행은 엔티티 적재 없이 스칼라로 주문 id만 구한 뒤 잠근다. 만료(ExpirePaymentService)·실패·취소
     * 경로(cancelOne 조건부 UPDATE)와 같은 Order → Payment → Inventory 순서다.
     *
     * <p><b>충돌 기록(Track 104-2 D-216·invariants P1)</b>: PG에서 일어난 사실이 내부 규칙과 충돌하면(같은 주문 두 번째 결제·종료 주문의 늦은
     * 승인·종결 결제의 성공·다른 결제에 기록된 pgTid·결제 불가 품목·PAID 결제의 취소·매칭 결제 없음) 거부·롤백하지 않고, <b>상태를 바꾸기
     * 전에</b> 판정해 불일치 1행을 기록한 뒤 정상 종료한다 — 결제·주문·재고는 그대로다. 주문 id를 알면 주문 쓰기 락 아래에서 기록한다.
     * 매칭 결제 없음은 결제 시작 커밋 전에 도착한 통지일 수 있어 웹훅이 422로 PG 재전송을 부르고, 재전송이 같은 종류로 매칭돼 상태 전이까지
     * 끝나면 그 행을 자동 해소한다(충돌·멱등 NO-OP는 해소하지 않음).
     *
     * @return 충돌로 기록한 불일치 유형(정상 전이·멱등 NO-OP면 빈 값). 웹훅은 매칭 없음만 422·그 외 200, mock 콜백(구매자 화면)은 기존 4xx로 알린다
     * @throws InvalidCallbackException 결제 행은 있는데 주문 행이 없는 경우(FK상 도달 경로 미발견)
     */
    public Optional<ReconciliationIssueType> handleCallback(PaymentCallbackCommand command) {
        if (command == null || command.callbackType() == null
                || command.paymentAttemptKey() == null || command.occurredAt() == null) {
            throw new IllegalArgumentException("콜백 입력 누락(callbackType·paymentAttemptKey·occurredAt).");
        }
        // Track 104-1 D-215(P5): 결제 행보다 주문 행을 먼저 잠근다(모든 쓰기 경로 공통 첫 락). 행 존재도 이 스칼라 조회로 판정한다 —
        // PG 콜백은 결제 시작 커밋과 경합할 수 있어, 스칼라는 비었는데 바로 뒤 엔티티 조회가 커밋을 보면 주문 락 없이 진행하게 된다.
        Optional<Long> orderId = paymentRepository.findOrderIdByPaymentAttemptKey(command.paymentAttemptKey());
        if (orderId.isEmpty()) {
            // 매칭 결제 없음(미결제 주문 hard delete 뒤 늦은 통지·결제 시작 롤백 등) — 주문을 모르므로 락 없이 통지 원문만 기록한다.
            log.warn("[Payment] 매칭 결제 없는 PG 통지 → 불일치 기록: attemptKey={}, callbackType={}",
                    command.paymentAttemptKey(), command.callbackType());
            reconciliationIssueRecorder.record(ReconciliationIssueType.PG_UNMATCHED_CALLBACK,
                    ReconciliationIssueRecorder.unmatchedPaymentKey(command.paymentAttemptKey(), command.callbackType().name()),
                    ReconciliationIssueRefs.unmatched(command.pgTid(), null),
                    callbackDetail(command, "NO_MATCHING_PAYMENT", null));
            return Optional.of(ReconciliationIssueType.PG_UNMATCHED_CALLBACK);
        }
        orderService.lockForWrite(orderId.get());

        Payment payment = paymentRepository.findByPaymentAttemptKey(command.paymentAttemptKey())
                .orElseThrow(() -> new InvalidCallbackException(
                        "결제 행을 찾을 수 없습니다: attemptKey=" + command.paymentAttemptKey()));
        // D-173: 행 락 + 최신 상태 재적재(@Lock 조회는 1차 캐시 엔티티를 덮어쓰지 않으므로 refresh·D-168 트랩).
        entityManager.refresh(payment, LockModeType.PESSIMISTIC_WRITE);
        PaymentStatus statusBefore = payment.getStatus();

        Optional<ReconciliationIssueType> conflict = switch (command.callbackType()) {
            case SUCCESS -> handleSuccess(payment, command);
            case FAILURE -> {
                handleFailure(payment, command);
                yield Optional.empty();
            }
            case CANCEL -> handleCancel(payment, command);
        };
        if (conflict.isPresent()) {
            return conflict; // 상태 무변경 — 저장·발행할 것이 없다
        }

        // D-29: pull → save(flush) → 동기 발행. 상태 무변경(NO-OP) 시 events 비어 있고 save는 dirty 없음.
        List<Object> events = payment.pullDomainEvents();
        paymentRepository.save(payment);
        flushPaymentOrRejectPgTidConflict(command);
        events.forEach(eventPublisher::publishEvent);
        if (payment.getStatus() != statusBefore) {
            // 같은 종류 통지의 정상 처리(상태 전이·동기 핸들러)가 끝난 직후에만 — 앞서 매칭 없음(결제 시작 커밋 전 도착)으로 기록된 같은 통지를
            // 자동 해소한다. 멱등 NO-OP·충돌 기록은 사실이 반영된 것이 아니라 해소하지 않는다(D-216 결정 1·외부 검토 지적 2).
            reconciliationIssueRecorder.resolveUnmatchedOnMatch(
                    ReconciliationIssueRecorder.unmatchedPaymentKey(command.paymentAttemptKey(), command.callbackType().name()));
        }
        return Optional.empty();
    }

    /**
     * PG 결제 통지 충돌을 불일치로 기록한다(Track 104-2 D-216). 중복 키는 결제 id라 같은 결제에 대한 재전송·사유 변화(예: 늦은 승인 뒤 만료)는
     * 1행으로 모인다. 호출 시점은 주문 쓰기 락 아래·상태 변경 전이다.
     */
    private Optional<ReconciliationIssueType> recordConflict(ReconciliationIssueType type, Payment payment,
            PaymentCallbackCommand command, String reason, OrderStatus orderStatus) {
        Map<String, Object> detail = callbackDetail(command, reason, payment.getStatus());
        if (orderStatus != null) {
            detail.put("orderStatus", orderStatus.name());
        }
        reconciliationIssueRecorder.record(type, "payment:" + payment.getId(),
                ReconciliationIssueRefs.ofPayment(payment.getOrderId(), payment.getId(), command.pgTid()), detail);
        return Optional.of(type);
    }

    /** 불일치 세부 — 사유·통지 원문 값·기록 시점 결제 상태(null 값은 담지 않는다). */
    private Map<String, Object> callbackDetail(PaymentCallbackCommand command, String reason, PaymentStatus paymentStatus) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        detail.put("callbackType", command.callbackType().name());
        detail.put("attemptKey", command.paymentAttemptKey());
        detail.put("occurredAt", command.occurredAt().toString());
        if (command.provider() != null) {
            detail.put("provider", command.provider());
        }
        if (command.pgTid() != null) {
            detail.put("pgTid", command.pgTid());
        }
        if (paymentStatus != null) {
            detail.put("paymentStatus", paymentStatus.name());
        }
        return detail;
    }

    /**
     * 결제 행을 명시적으로 flush해 uk_payment_provider_pg_tid 위반을 트랜잭션 안에서 표면화한다(Track 93 D-198). 커밋 시점까지 미루면
     * 예외가 @Transactional 경계 밖에서 터져 500으로 새므로(RED 실측), 해당 제약만 409로 변환하고 그 외 무결성 위반은 은닉하지 않고 그대로
     * 전파한다(90-C {@code SellerProductCommandService} 선례). NO-OP(dirty 없음)는 flush가 무작업이다.
     */
    private void flushPaymentOrRejectPgTidConflict(PaymentCallbackCommand command) {
        try {
            paymentRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            String cause = exception.getMostSpecificCause().getMessage();
            if (cause == null || !cause.contains(PG_TID_CONSTRAINT)) {
                throw exception;
            }
            log.warn("[Payment] 콜백 pgTid 충돌 차단(409·{}) attemptKey={} provider={} pgTid={}: {}",
                    PG_TID_CONSTRAINT, command.paymentAttemptKey(), command.provider(), command.pgTid(), cause);
            throw new PaymentPgTidConflictException(
                    "이미 다른 결제에 기록된 PG 거래 ID입니다(" + PG_TID_CONSTRAINT + "): pgTid=" + command.pgTid());
        }
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
        try {
            payment.cancel(); // PAID → CANCELLED (PAY-2·canTransitionTo 강제)
        } catch (IllegalStateException exception) {
            // D-172 보충: 환불 콜백 동기 체인에서 전이 불가(비PAID)는 422로 콜백을 롤백한다(500 fallback 금지).
            throw new PaymentInvalidStateException("결제를 취소 상태로 전이할 수 없습니다: " + exception.getMessage());
        }
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

    /**
     * SUCCESS 콜백 처리(D-34): PENDING→PAID / PAID 멱등 NO-OP / FAILED·CANCELLED·EXPIRED는 불일치 기록(Track 104-2 D-216 — 구 REJECT 422).
     *
     * @return 충돌로 기록한 불일치 유형(전이·NO-OP면 빈 값)
     */
    private Optional<ReconciliationIssueType> handleSuccess(Payment payment, PaymentCallbackCommand command) {
        return switch (payment.getStatus()) {
            case PENDING -> approvePending(payment, command);
            case PAID -> {
                log.info("[Payment] SUCCESS 콜백 멱등 NO-OP(이미 PAID): attemptKey={}", command.paymentAttemptKey());
                yield Optional.empty();
            }
            case FAILED, CANCELLED, EXPIRED -> {
                log.warn("[Payment] SUCCESS 콜백 충돌(종결 결제): 상태={}, attemptKey={}", payment.getStatus(), command.paymentAttemptKey());
                yield recordConflict(ReconciliationIssueType.PG_PAYMENT_SUCCESS_CONFLICT, payment, command, "TERMINAL_PAYMENT", null);
            }
        };
    }

    /**
     * PENDING 결제의 승인. 충돌 판정(PAY-3a·늦은 승인·결제 불가 품목·pgTid 중복)을 전부 {@code payment.complete} 전에 끝낸다 — 하나라도 걸리면
     * 불일치만 기록하고 결제·주문·재고는 건드리지 않는다(Track 104-2 D-216·P1).
     */
    private Optional<ReconciliationIssueType> approvePending(Payment payment, PaymentCallbackCommand command) {
        // PAY-3a 재검증: 동일 주문에 PAID 행이 이미 있으면 성립 불가(만료 PENDING 지연 콜백 anomaly·D-32)
        if (paymentRepository.existsByOrderIdAndStatus(payment.getOrderId(), PaymentStatus.PAID)) {
            log.warn("[Payment] SUCCESS 콜백 충돌(PAY-3a): orderId={}, attemptKey={} — 이미 PAID 행 존재",
                    payment.getOrderId(), command.paymentAttemptKey());
            return recordConflict(ReconciliationIssueType.PG_PAYMENT_SUCCESS_CONFLICT, payment, command, "DUPLICATE_PAID_PAYMENT", null);
        }
        Order order = reloadOrderForApproval(payment.getOrderId());
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            // 늦은 승인: 종료로 해제된 예약분(타 주문 예약분)을 차감하지 않도록 재고 확정 전에 멈춘다(D-173).
            log.warn("[Payment] SUCCESS 콜백 충돌(늦은 승인): orderId={}, orderStatus={}, attemptKey={}",
                    order.getId(), order.getStatus(), command.paymentAttemptKey());
            return recordConflict(ReconciliationIssueType.PG_PAYMENT_SUCCESS_CONFLICT, payment, command,
                    "ORDER_NOT_PENDING_PAYMENT", order.getStatus());
        }
        // 도달 경로 미발견: 결제 대기 주문의 품목은 ORDERED뿐이다(결제 전 품목 전이 경로 없음). 동기 핸들러의 품목 PAID 전이 실패(500)를 전이 전에 판정한다.
        if (order.getItems().stream().anyMatch(item -> !item.getItemStatus().canTransitionTo(OrderItemStatus.PAID))) {
            log.warn("[Payment] SUCCESS 콜백 충돌(결제 불가 품목): orderId={}, attemptKey={}", order.getId(), command.paymentAttemptKey());
            return recordConflict(ReconciliationIssueType.PG_PAYMENT_SUCCESS_CONFLICT, payment, command, "ITEM_NOT_PAYABLE", order.getStatus());
        }
        // PAY-3b: 같은 (provider, pgTid)가 다른 결제에 이미 있으면 UNIQUE 위반(구 409) 전에 기록한다. 다른 주문의 동시 승인과의 경합은
        // 아래 flush의 409가 그대로 막는다(그 경우는 롤백).
        if (command.pgTid() != null && paymentRepository.existsByPgProviderAndPgTid(command.provider(), command.pgTid())) {
            log.warn("[Payment] SUCCESS 콜백 충돌(pgTid 중복): attemptKey={}, pgTid={}", command.paymentAttemptKey(), command.pgTid());
            return recordConflict(ReconciliationIssueType.PG_TID_CONFLICT, payment, command, "PG_TID_ALREADY_USED", null);
        }
        // 재고 확정 실패(INV-3·4)는 동기 핸들러 예외로 롤백된다 — 재고 규칙을 결제 서비스에 복제하지 않는다(도달 경로 미발견·D-216).
        payment.complete(command.occurredAt(), command.provider(), command.pgTid());
        return Optional.empty();
    }

    /**
     * 승인 전 Order를 최신 상태로 재적재한다(D-173). 주문 쓰기 락은 {@link #handleCallback} 첫 문장에서 이미 잡혀 있어(Track 104-1) 여기
     * refresh는 같은 락 위의 최신 재적재다. 잠근 엔티티는 1차 캐시에 남아 후속 OrderEventHandler.markPaid가 같은 인스턴스로 전이한다.
     *
     * @throws InvalidCallbackException 주문 행 없음(결제 행의 order_id FK상 도달 경로 미발견)
     */
    private Order reloadOrderForApproval(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new InvalidCallbackException("결제 대상 주문을 찾을 수 없습니다: orderId=" + orderId));
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        return order;
    }

    /**
     * FAILURE 콜백 처리(D-34·FE-12c 정정): PENDING→PG 실제 결제 실패(Payment FAILED·failure_code 유지) + Order를
     * PAYMENT_EXPIRED 종료(cancelOne·OrderTerminated 발행) / 종결 상태 NO-OP. FAILED(실제 실패)는 EXPIRED(결제창 이탈·만료)와
     * 구분되나, Order는 셋 다 동일하게 PAYMENT_EXPIRED로 종료된다.
     */
    private void handleFailure(Payment payment, PaymentCallbackCommand command) {
        if (payment.getStatus() == PaymentStatus.PENDING) {
            payment.fail(resolveFailureCode(command));   // PENDING → FAILED (PaymentFailed 미발행)
            orderAutoCancelService.cancelOne(payment.getOrderId());            // Order PAYMENT_EXPIRED + OrderTerminated 발행
        } else {
            log.info("[Payment] FAILURE 콜백 NO-OP: 상태={}, attemptKey={}", payment.getStatus(), command.paymentAttemptKey());
        }
    }

    /**
     * CANCEL 콜백 처리(D-34·FE-12c·D-198 정정): PAID→불일치 기록(결제 PAID 유지) / PENDING→미결제 종료(결제창 취소·Payment EXPIRED +
     * Order PAYMENT_EXPIRED) / 종결 상태(FAILED·CANCELLED·EXPIRED) NO-OP.
     *
     * <p><b>PAID는 전이하지 않는다(Track 93 D-198)</b>: PAID→CANCELLED는 환불 완료(Refund COMPLETED·{@code PaymentRefundCompletedHandler}·D-71)와
     * 관리자 수동 보정({@link #markCancelledByAdmin})만의 전이다. 콜백이 이를 우회하면 환불 행 없이 결제만 CANCELLED가 되어 이후 클레임 환불이
     * PAID 행을 찾지 못하고(RefundService) 되돌릴 경로도 없다. Track 104-2부터는 거부(422) 대신 PG 취소 사실을 불일치로 남긴다(D-216·P1).
     *
     * @return 충돌로 기록한 불일치 유형(PAID일 때만)
     */
    private Optional<ReconciliationIssueType> handleCancel(Payment payment, PaymentCallbackCommand command) {
        switch (payment.getStatus()) {
            case PAID -> {
                log.warn("[Payment] CANCEL 콜백 충돌(PAID·환불 우회 차단): attemptKey={}", command.paymentAttemptKey());
                return recordConflict(ReconciliationIssueType.PG_PAYMENT_CANCEL_ON_PAID, payment, command, "CANCEL_ON_PAID", null);
            }
            case PENDING -> terminateUnpaid(payment);
            case FAILED, CANCELLED, EXPIRED ->
                    log.info("[Payment] CANCEL 콜백 NO-OP: 상태={}, attemptKey={}", payment.getStatus(), command.paymentAttemptKey());
        }
        return Optional.empty();
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
