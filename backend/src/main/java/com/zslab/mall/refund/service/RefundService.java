package com.zslab.mall.refund.service;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.service.OrderService;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.gateway.PgRefundResponse;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.repository.PaymentRepository;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueType;
import com.zslab.mall.reconciliation.service.ReconciliationIssueRecorder;
import com.zslab.mall.reconciliation.service.ReconciliationIssueRefs;
import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.enums.RefundCallbackStatus;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.refund.exception.RefundIdempotentNoOpException;
import com.zslab.mall.refund.exception.RefundInvariantViolationException;
import com.zslab.mall.refund.exception.RefundNotFoundException;
import com.zslab.mall.refund.repository.RefundRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * 소비 핸들러(ClaimRefundCompletedHandler·PaymentRefundCompletedHandler)는 D-172부터 {@code @EventListener} 동기 소비라 Claim 종결·품목 종결·
 * 재고 복구·Payment CANCELLED가 같은 TX에 묶인다(알림은 AFTER_COMMIT 유지). 행 락 순서는 {@link #lockClaimThenRefund} 참조.
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
    private final EntityManager entityManager;
    private final AuditRecorder auditRecorder;
    private final OrderService orderService;
    private final ReconciliationIssueRecorder reconciliationIssueRecorder;

    public RefundService(
            ClaimRepository claimRepository,
            OrderItemRepository orderItemRepository,
            PaymentRepository paymentRepository,
            RefundRepository refundRepository,
            PaymentGateway paymentGateway,
            TracedEventPublisher eventPublisher,
            EntityManager entityManager,
            AuditRecorder auditRecorder,
            OrderService orderService,
            ReconciliationIssueRecorder reconciliationIssueRecorder) {
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
        this.auditRecorder = auditRecorder;
        this.orderService = orderService;
        this.reconciliationIssueRecorder = reconciliationIssueRecorder;
    }

    /**
     * 환불을 시작한다(expected-spec §5.1·§7). Claim.APPROVED 검증(CLM-3) → PAY-1 사전 누적 검증 → Refund(PENDING) INSERT →
     * PG 환불 요청 등록 순서다. PG 응답의 pg_refund_id를 PENDING 행에 부여해 이후 webhook 콜백 매칭 키로 쓴다.
     *
     * <p><b>PG 호출 예외(D-67·CR-03)</b>: {@code gateway.refund()}가 예외(네트워크·timeout·gateway)를 던지면 PENDING 행을
     * FAILED로 전이해 영구 PENDING 잔존을 차단한다. 별도 failure_reason 컬럼은 두지 않고 로깅만 남긴다(CR-01 보류).
     *
     * <p><b>멱등 게이트(D-94 Q6·D-172 행 락)</b>: 클레임 행을 {@code refresh(PESSIMISTIC_WRITE)}로 잠근 뒤 동일 claimId에 활성
     * Refund(PENDING/COMPLETED)가 있으면 신규 생성 없이 기존 행을 no-op 반환한다. 락으로 동시 initiate(자동 핸들러 + 복구 스케줄러·
     * 이벤트 재전달)가 직렬화돼 두 번째 진입은 첫 번째가 만든 행을 본다(환불 1건·PG 호출 1회). FAILED는 활성에서 제외해 RFN-2 재시도와
     * 충돌하지 않는다. refresh인 이유: 호출부가 클레임을 1차 캐시에 올려둔 뒤라 잠금 조회만으로는 stale 상태가 덮이지 않는다(Track 79 트랩).
     *
     * @param claimId 환불 대상 클레임 id(APPROVED여야 함·CLM-3)
     * @param amount  환불 금액(KRW 정수·1 이상)
     * @return 생성된 Refund(정상 시 PENDING+pg_refund_id, PG 예외 시 FAILED)
     * @throws ClaimNotFoundException          클레임이 없는 경우
     * @throws ClaimInvalidStateException      클레임이 APPROVED가 아닌 경우(CLM-3)
     * @throws RefundInvariantViolationException 품목 상한·PAY-1 사전 한도 초과(과환불 차단)
     */
    public Refund initiate(Long claimId, long amount) {
        if (claimId == null) {
            throw new IllegalArgumentException("claimId는 필수입니다.");
        }
        if (amount < 1) {
            throw new IllegalArgumentException("환불금액은 1 이상이어야 합니다. 입력: " + amount);
        }
        // Track 104-1 D-215(P5): 클레임 행 락보다 주문 쓰기 락을 먼저 잡는다(환불 경로 락 순서 Order → Claim → Refund → Payment).
        claimRepository.findOrderIdById(claimId).ifPresent(orderService::lockForWrite);

        // CLM-3 전제: 클레임 행 락(D-172) — 동시 initiate 직렬화 후 멱등 게이트·상태를 최신 값으로 재확인한다.
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
        entityManager.refresh(claim, LockModeType.PESSIMISTIC_WRITE);

        // 멱등 게이트(D-94 Q6 α′·락 이후 잠금 읽기로 재확인): 동일 claimId에 활성 Refund(PENDING/COMPLETED) 존재 시 신규 생성 없이 기존 행을
        // no-op 반환한다. 잠금 읽기인 이유: REPEATABLE READ에서 비잠금 exists는 TX 첫 읽기 스냅샷에 묶여 락 대기 중 커밋된 행을 못 본다(D-172).
        // 인메모리 ApplicationEvent 가정 하 이벤트 재전달·운영 재실행·중복 진입을 차단한다(외부 브로커·Outbox 도입 시 재검토·D-94 박제).
        // Track 104-3a: PG 성공이 기록된 FAILED 행도 활성이다 — PG에서 이미 돈이 나갔으므로 새 행(= PG 재환불)을 만들지 않는다.
        List<Refund> activeRefunds = refundRepository.findRefundedByClaimIdForUpdate(claimId);
        if (!activeRefunds.isEmpty()) {
            return activeRefunds.get(0);
        }

        // CLM-3: Claim.APPROVED 후에만 환불 생성
        if (claim.getStatus() != ClaimStatus.APPROVED) {
            throw new ClaimInvalidStateException(
                    "환불은 APPROVED 클레임에서만 생성할 수 있습니다(CLM-3). 현재 상태: " + claim.getStatus());
        }

        // payment 해소: claim → order_item → order → PAID payment
        Payment payment = resolvePaidPayment(claim);

        // 품목 상한(Track 104-3a): 기환불액 + 신규 amount ≤ 품목 금액. 결제 단위 PAY-1만으로는 한 품목 환불이 형제 품목 금액까지 나갈 수 있다.
        long itemTotalPrice = orderItemRepository.findById(claim.getOrderItemId())
                .orElseThrow(() -> new IllegalStateException("주문 품목을 찾을 수 없습니다: orderItemId=" + claim.getOrderItemId()))
                .getTotalPrice();
        long itemRefunded = refundRepository.sumRefundedByOrderItemId(claim.getOrderItemId());
        if (itemRefunded + amount > itemTotalPrice) {
            throw new RefundInvariantViolationException(
                    "품목 상한 위반(사전): 기환불 " + itemRefunded + " + 신규 " + amount + " > 품목 금액 " + itemTotalPrice);
        }

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
            PgRefundResponse response = paymentGateway.refund(payment.getPgTid(), amount);
            refund.assignPgRefundId(response.pgRefundId());
        } catch (RuntimeException gatewayException) {
            log.warn("[Refund] PG 환불 요청 예외 → FAILED 전이(D-67): claimId={}, refundId={}, 원인={}",
                    claimId, refund.getId(), gatewayException.toString());
            refund.markFailed();
        }
        return refundRepository.save(refund);
    }

    /**
     * 운영자 수동 환불 개시(D-106 §4·admin actor wrapper). primitive {@link #initiate}에 1:1 위임하며 actor 파라미터는
     * 수신하지 않는다(D-92 원칙·initiate가 이미 actor 비의존 시그니처).
     *
     * <p><b>용도</b>: 자동 트리거({@code ClaimApprovedHandler}·{@code ClaimPickedUpHandler})가 PG 장애·도메인 위반으로
     * 실패해 Claim이 환불 없이 APPROVED로 잔존할 때, 운영자가 동일 Claim에 대해 환불을 수동 재개시하는 fallback 경로다.
     * D-94 Q8 원문 "재시도 허용(운영자/Job 재 initiate·RFN-2)" 정합.
     *
     * <p>멱등 게이트(initiate)가 동일 claimId 활성 Refund(PENDING/COMPLETED) 존재 시 기존 행을 no-op 반환하므로 중복 개시는
     * 안전하다. {@code @Transactional}은 클래스 레벨이 커버하므로 별도 부여하지 않는다.
     *
     * @param claimId 환불 대상 클레임 id(APPROVED여야 함·CLM-3)
     * @param amount  환불 금액(KRW 정수·1 이상)
     * @return 생성된 Refund(정상 시 PENDING+pg_refund_id, PG 예외 시 FAILED)
     * @throws ClaimNotFoundException          클레임이 없는 경우(404)
     * @throws ClaimInvalidStateException      클레임이 APPROVED가 아닌 경우(CLM-3·422)
     * @throws RefundInvariantViolationException 품목 상한·PAY-1 사전 한도 초과(과환불 차단·422)
     */
    public Refund initiateByAdmin(Long claimId, long amount, AuditContext auditContext) {
        Refund refund = initiate(claimId, amount);
        // Track 101-A: 수동 환불 개시는 금액을 운영자가 직접 입력하고 되돌릴 수 없는데 행위자 기록이 없었다.
        auditRecorder.record(auditContext, AuditLogAction.CREATE, PolymorphicTargetType.REFUND, refund.getId(),
                Map.of(),
                Map.of("claimId", claimId, "amount", amount, "status", refund.getStatus().name()));
        return refund;
    }

    /**
     * 환불 완료를 적용한다(PENDING → COMPLETED·expected-spec §5.1). pg_refund_id로 행을 찾고 RFN-1·RFN-3·PAY-1 사후를 가드한 뒤
     * refunded_at을 시스템 시각(D-70)으로 채우고 {@code RefundCompleted}를 발행한다(save→publish·D-29).
     *
     * <p><b>충돌 기록(Track 104-2 D-216·P1·P6)</b>: PAY-1 사후 초과·결제 취소 불가 상태는 거부(롤백) 대신 전이 전에 불일치를 기록하고 PENDING
     * 그대로 돌려준다. 전이 뒤에는 교환·거부 클레임 환불·확정 품목 있는 전액 환불을 불일치로 남긴다(저장·전이는 그대로).
     *
     * @param pgRefundId webhook 콜백이 운반한 PG 환불 식별자(매칭 키·RFN-1 필수)
     * @return COMPLETED로 전이된 Refund(충돌 기록 시 PENDING 그대로)
     * @throws RefundInvariantViolationException pg_refund_id 누락(RFN-1)
     * @throws RefundNotFoundException            pg_refund_id 미매칭
     * @throws RefundIdempotentNoOpException      이미 COMPLETED인 환불 재호출(RFN-3 멱등 시그널)
     */
    public Refund markCompleted(String pgRefundId) {
        // RFN-1: pg_refund_id 없이 COMPLETED 전이 불가
        if (pgRefundId == null || pgRefundId.isBlank()) {
            throw new RefundInvariantViolationException("RFN-1 위반: pg_refund_id 없이 COMPLETED 전이 불가.");
        }
        // Track 104-1 D-215(P5): 직접 호출 경로도 주문 쓰기 락이 먼저다(handleCallback 경유면 이미 쥔 락). 행 존재도 이 스칼라 조회로
        // 판정한다(PG 콜백이 환불 개시 커밋과 경합할 때 락 없이 진행하는 창 차단 — handleCallback과 같은 이유).
        orderService.lockForWrite(refundRepository.findOrderIdByPgRefundId(pgRefundId)
                .orElseThrow(() -> new RefundNotFoundException("환불 행을 찾을 수 없습니다: pgRefundId=" + pgRefundId)));
        Refund refund = refundRepository.findByPgRefundId(pgRefundId)
                .orElseThrow(() -> new RefundNotFoundException("환불 행을 찾을 수 없습니다: pgRefundId=" + pgRefundId));
        // D-172 보충(락 순서 Claim → Refund → Payment): 동시 SUCCESS 콜백 N건 중 1건만 COMPLETED 전이·이벤트 발행(나머지는 종결 no-op)
        Claim claim = lockClaimThenRefund(refund);

        // RFN-3: 이미 COMPLETED면 멱등 no-op 시그널(직접 호출 경로·webhook은 handleCallback에서 선검사)
        if (refund.getStatus() == RefundStatus.COMPLETED) {
            throw new RefundIdempotentNoOpException("이미 완료된 환불 콜백입니다(RFN-3): pgRefundId=" + pgRefundId);
        }

        // PAY-1 사후 재검증(D-68): Payment 행 비관적 락(순서 3번째)으로 동시 환불 직렬화 후 누적 한도 재확인. 이후 동기 체인
        // (Claim COMPLETED·품목·재고·Payment CANCELLED)은 이미 잡은 Claim·Payment 락 위에서 OrderItem → Order → Inventory 순으로 진행된다.
        Payment payment = paymentRepository.findByIdForUpdate(refund.getPaymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "환불 대상 결제를 찾을 수 없습니다: paymentId=" + refund.getPaymentId()));
        long completedAfter = refundRepository.sumCompletedByPaymentId(refund.getPaymentId()) + refund.getAmount();
        if (completedAfter > payment.getAmount()) {
            // Track 104-2 D-216(P1): PG에서 끝난 환불을 거부(422·롤백)하지 않는다 — 환불은 PENDING 그대로 두고 초과 사실을 불일치로 남긴다.
            log.warn("[Refund] 환불 완료 충돌(PAY-1 사후 초과): refundId={}, 완료 후 합계={}, 결제액={}",
                    refund.getId(), completedAfter, payment.getAmount());
            recordRefundIssue(ReconciliationIssueType.PG_REFUND_EXCEEDS_PAYMENT, "refund:" + refund.getId(), refund, payment,
                    refundDetail("PAY1_EXCEEDED", refund, payment, completedAfter));
            return refund;
        }
        // 도달 경로 미발견: Refund.paymentId는 개시 시점의 PAID 결제이고 PAID는 CANCELLED로만 간다. 전액 완료인데 결제가 PAID·CANCELLED가
        // 아니면 동기 체인의 결제 취소 전이가 422로 실패하므로 환불 전이 전에 판정한다(D-216).
        if (completedAfter == payment.getAmount()
                && payment.getStatus() != PaymentStatus.PAID && payment.getStatus() != PaymentStatus.CANCELLED) {
            log.warn("[Refund] 환불 완료 충돌(결제 취소 불가 상태): refundId={}, paymentStatus={}", refund.getId(), payment.getStatus());
            recordRefundIssue(ReconciliationIssueType.FULL_REFUND_PAYMENT_NOT_CANCELLED, "refund:" + refund.getId(), refund, payment,
                    refundDetail("PAYMENT_NOT_CANCELLABLE", refund, payment, completedAfter));
            return refund;
        }

        // 전이 + refunded_at 시스템 시각(D-70) + 이벤트 누적
        refund.markCompleted(LocalDateTime.now());
        List<Object> events = refund.pullDomainEvents();
        refundRepository.save(refund);
        recordCompletionIssues(refund, payment, claim, completedAfter);
        events.forEach(eventPublisher::publishEvent); // D-29 save→publish
        return refund;
    }

    /**
     * 환불 완료 시점 불일치 기록(Track 104-2 D-216·P6) — 저장·전이는 그대로 두고 표식만 남긴다. 클레임 상태는 동기 체인이 바꾸기 전 값으로 본다.
     * <ul>
     *   <li>교환 클레임·거부된 클레임에 환불 완료(REFUND_ON_INVALID_CLAIM) — 체인은 이 클레임을 종결하지 않고 건너뛴다</li>
     *   <li>구매확정 품목이 있는 주문의 전액 환불(FULL_REFUND_WITH_CONFIRMED_ITEM) — 결제는 기존대로 CANCELLED가 된다</li>
     * </ul>
     */
    private void recordCompletionIssues(Refund refund, Payment payment, Claim claim, long completedAfter) {
        if (claim.getType() == ClaimType.EXCHANGE || claim.getStatus() == ClaimStatus.REJECTED) {
            Map<String, Object> detail = refundDetail("CLAIM_NOT_REFUNDABLE", refund, payment, completedAfter);
            detail.put("claimType", claim.getType().name());
            detail.put("claimStatus", claim.getStatus().name());
            recordRefundIssue(ReconciliationIssueType.REFUND_ON_INVALID_CLAIM, "refund:" + refund.getId(), refund, payment, detail);
        }
        if (completedAfter == payment.getAmount()
                && orderItemRepository.existsByOrderIdAndItemStatus(payment.getOrderId(), OrderItemStatus.CONFIRMED)) {
            recordRefundIssue(ReconciliationIssueType.FULL_REFUND_WITH_CONFIRMED_ITEM, "payment:" + payment.getId(), refund, payment,
                    refundDetail("FULL_REFUND_WITH_CONFIRMED_ITEM", refund, payment, completedAfter));
        }
    }

    private void recordRefundIssue(ReconciliationIssueType type, String dedupeKey, Refund refund, Payment payment,
            Map<String, Object> detail) {
        reconciliationIssueRecorder.record(type, dedupeKey, ReconciliationIssueRefs.ofRefund(payment.getOrderId(), payment.getId(),
                refund.getId(), refund.getClaimId(), refund.getPgRefundId()), detail);
    }

    /** 환불 불일치 세부 — 사유·금액·결제 상태. */
    private Map<String, Object> refundDetail(String reason, Refund refund, Payment payment, long completedAfter) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        detail.put("refundAmount", refund.getAmount());
        detail.put("completedTotalAfter", completedAfter);
        detail.put("paymentAmount", payment.getAmount());
        detail.put("paymentStatus", payment.getStatus().name());
        return detail;
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
     * <p><b>충돌 기록(Track 104-2 D-216·P1)</b>: 매칭 환불이 없는 통지(구 404·로그 없음)와 실패 처리된 환불의 성공 통지(구 500)는 거부 대신
     * 불일치를 기록하고 정상 종료한다(상태 불변). 완료된 환불의 실패 통지(구 500·Track 104-3a)도 같다. 매칭 환불이 없으면 주문을 모르므로 락 없이 통지 원문만 기록한다. 매칭 없음은 환불 개시 커밋
     * 전에 도착한 통지일 수 있어 웹훅은 기록 커밋 뒤 기존 404로 PG 재전송을 부르고, 재전송이 같은 종류로 매칭돼 상태 전이까지 끝나면 그 행을
     * 자동 해소한다(결정 1·외부 검토 지적 2 — 충돌·멱등 NO-OP는 해소하지 않음).
     *
     * @return 충돌로 기록한 불일치 유형(매칭 없음·실패 환불의 성공·완료 환불의 실패). 정상 처리·멱등·완료 처리 중 기록(markCompleted)은 빈 값
     */
    public Optional<ReconciliationIssueType> handleCallback(String pgRefundId, RefundCallbackStatus status, String failureReason) {
        if (pgRefundId == null || pgRefundId.isBlank() || status == null) {
            throw new IllegalArgumentException("환불 콜백 입력 누락(pgRefundId·status).");
        }
        // Track 104-1 D-215(P5): 환불·클레임을 적재하기 전에 주문 쓰기 락을 먼저 잡는다(형제 품목 확정·클레임과 직렬화). 행 존재도 이
        // 스칼라 조회로 판정한다 — PG 콜백이 환불 개시 커밋과 경합하면 스칼라는 비었는데 뒤 엔티티 조회가 커밋을 봐 락 없이 진행할 수 있다.
        Optional<Long> orderId = refundRepository.findOrderIdByPgRefundId(pgRefundId);
        if (orderId.isEmpty()) {
            log.warn("[Refund] 매칭 환불 없는 PG 통지 → 불일치 기록: pgRefundId={}, status={}", pgRefundId, status);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", "NO_MATCHING_REFUND");
            detail.put("callbackStatus", status.name());
            if (failureReason != null) {
                detail.put("failureReason", failureReason);
            }
            reconciliationIssueRecorder.record(ReconciliationIssueType.PG_UNMATCHED_CALLBACK,
                    ReconciliationIssueRecorder.unmatchedRefundKey(pgRefundId, status.name()), ReconciliationIssueRefs.unmatched(null, pgRefundId),
                    detail);
            return Optional.of(ReconciliationIssueType.PG_UNMATCHED_CALLBACK);
        }
        orderService.lockForWrite(orderId.get());
        Refund refund = refundRepository.findByPgRefundId(pgRefundId)
                .orElseThrow(() -> new RefundNotFoundException("환불 행을 찾을 수 없습니다: pgRefundId=" + pgRefundId));
        // D-172 보충: 비잠금 조회로 claimId를 얻은 뒤 Claim → Refund 순으로 잠그고 종결 여부를 재확인한다(initiate와 같은 순서·교착 제거).
        lockClaimThenRefund(refund);

        switch (status) {
            case SUCCESS -> {
                if (refund.getStatus() == RefundStatus.COMPLETED) {
                    log.info("[Refund] SUCCESS 콜백 멱등 NO-OP(이미 COMPLETED·RFN-3): pgRefundId={}", pgRefundId);
                    return Optional.empty();
                }
                if (refund.getStatus() == RefundStatus.FAILED) {
                    // RFN-2: FAILED는 종결이라 COMPLETED로 되돌리지 않는다 — PG에서 끝난 환불 사실은 불일치로 남긴다(구 500·롤백).
                    // Track 104-3a: 사실은 환불 행에도 남긴다 — 품목 기환불액·재개시 판정(RefundedCondition)이 이 행을 "환불된 금액"으로 센다.
                    log.warn("[Refund] SUCCESS 콜백 충돌(이미 FAILED): pgRefundId={}, refundId={}", pgRefundId, refund.getId());
                    refund.recordPgRefundSucceeded(LocalDateTime.now());
                    refundRepository.save(refund);
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("reason", "REFUND_ALREADY_FAILED");
                    detail.put("refundAmount", refund.getAmount());
                    reconciliationIssueRecorder.record(ReconciliationIssueType.PG_REFUND_SUCCESS_ON_FAILED, "refund:" + refund.getId(),
                            ReconciliationIssueRefs.ofRefund(orderId.get(), refund.getPaymentId(), refund.getId(), refund.getClaimId(),
                                    pgRefundId), detail);
                    return Optional.of(ReconciliationIssueType.PG_REFUND_SUCCESS_ON_FAILED);
                }
                // markCompleted는 PAY-1 초과·결제 취소 불가면 불일치만 기록하고 PENDING으로 돌려준다 — 그 경우는 정상 처리가 아니다.
                if (markCompleted(pgRefundId).getStatus() == RefundStatus.COMPLETED) {
                    resolveUnmatchedAfterProcessed(pgRefundId, status);
                }
            }
            case FAIL -> {
                if (refund.getStatus() == RefundStatus.FAILED) {
                    log.info("[Refund] FAIL 콜백 멱등 NO-OP(이미 FAILED): pgRefundId={}", pgRefundId);
                    return Optional.empty();
                }
                if (refund.getStatus() == RefundStatus.COMPLETED) {
                    // RFN-2: COMPLETED는 종결이라 FAILED로 되돌리지 않는다 — PG의 실패 통지는 불일치로 남기고 정상 종료한다(Track 104-3a·구 500·
                    // 롤백·PG 무한 재전송). 기록기는 이 트랜잭션에 참여하므로 예외 없이 끝나야 기록이 커밋된다.
                    log.warn("[Refund] FAIL 콜백 충돌(이미 COMPLETED): pgRefundId={}, refundId={}", pgRefundId, refund.getId());
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("reason", "REFUND_ALREADY_COMPLETED");
                    detail.put("refundAmount", refund.getAmount());
                    if (failureReason != null) {
                        detail.put("failureReason", failureReason);
                    }
                    reconciliationIssueRecorder.record(ReconciliationIssueType.PG_REFUND_FAIL_ON_COMPLETED, "refund:" + refund.getId(),
                            ReconciliationIssueRefs.ofRefund(orderId.get(), refund.getPaymentId(), refund.getId(), refund.getClaimId(),
                                    pgRefundId), detail);
                    return Optional.of(ReconciliationIssueType.PG_REFUND_FAIL_ON_COMPLETED);
                }
                markFailed(refund.getId(), failureReason);
                resolveUnmatchedAfterProcessed(pgRefundId, status);
            }
        }
        return Optional.empty();
    }

    /**
     * 같은 종류 통지의 정상 처리(상태 전이)가 끝난 직후에만 — 앞서 매칭 없음(환불 개시 커밋 전 도착)으로 기록된 같은 통지를 자동 해소한다.
     * 멱등 NO-OP·충돌 기록 분기는 부르지 않는다(D-216 결정 1·외부 검토 지적 2).
     */
    private void resolveUnmatchedAfterProcessed(String pgRefundId, RefundCallbackStatus status) {
        reconciliationIssueRecorder.resolveUnmatchedOnMatch(ReconciliationIssueRecorder.unmatchedRefundKey(pgRefundId, status.name()));
    }

    /**
     * 환불 경로 공통 락 순서(D-172 보충·외부 검토 2차 → Track 104-1 D-215): <b>Order(주문 쓰기 락) → Claim → Refund → Payment → OrderItem →
     * Inventory</b>. 주문 락은 각 진입점 첫 문장이 잡고 여기서는 그 아래 순서를 지킨다. 콜백 경로가 Refund를
     * 먼저 잡고 Claim을 나중에(markCompleted UPDATE) 잡으면 Claim을 먼저 잡는 initiate(복구 스케줄러·자동 핸들러)와 교착한다(T1 실측·PENDING 잔류).
     * refresh인 이유: 호출부가 1차 캐시에 올린 stale 엔티티를 잠금과 함께 최신으로 재적재한다(Track 79 트랩). 같은 TX에서 두 번 호출돼도 이미
     * 보유한 락이라 무해하다.
     */
    private Claim lockClaimThenRefund(Refund refund) {
        Claim claim = claimRepository.findById(refund.getClaimId())
                .orElseThrow(() -> new IllegalStateException("환불의 클레임 미존재: claimId=" + refund.getClaimId()));
        entityManager.refresh(claim, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(refund, LockModeType.PESSIMISTIC_WRITE);
        return claim;
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
