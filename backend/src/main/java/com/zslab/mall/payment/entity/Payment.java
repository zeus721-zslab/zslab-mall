package com.zslab.mall.payment.entity;

import com.zslab.mall.common.entity.AbstractPublicIdFullAuditableEntity;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.event.PaymentCompleted;
import com.zslab.mall.payment.event.PaymentFailed;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 결제(PAY Aggregate Root·ARCHIVE·public_id {@code pay_}).
 *
 * <p>독립 Aggregate Root다. Order는 {@code orderId}(Order.id)로 외부 참조하며 JPA 연관(@ManyToOne)을 두지 않는다
 * (aggregate-boundary.md·Payment는 Order Aggregate에 속하지 않음). 카디널리티는 Order 1 : Payment N이고,
 * 재시도는 기존 행 재사용 없이 항상 새 행을 생성한다(D-28).
 *
 * <p>equals/hashCode·toString은 {@link AbstractPublicIdFullAuditableEntity}가 publicId 기준으로 제공하므로
 * 본 클래스에서 재선언하지 않는다(Q8=C·base javadoc).
 *
 * <p><b>상태(PAY-2)</b>: status 전이는 {@link PaymentStatus#canTransitionTo}가 단일 책임으로 강제한다.
 * 상태 전이 도메인 메서드({@link #complete}·{@link #fail}·{@link #cancel})는 전이 직후 도메인 이벤트를 내부 목록에 누적하며,
 * {@link #pullDomainEvents}로 꺼낸 뒤 {@code PaymentService}가 발행한다(D-29).
 *
 * <p><b>키 2종</b>: {@code public_id}(pay_)는 외부 노출 식별자, {@code paymentAttemptKey}(pat_)는 결제 시도 식별자·콜백 매핑
 * 1차 키다(D-35). 둘 다 CHAR(30)이며 D-26 정합으로 {@code @JdbcTypeCode(SqlTypes.CHAR)}를 명시한다.
 */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends AbstractPublicIdFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "payment_attempt_key", length = 30, nullable = false, updatable = false)
    private String paymentAttemptKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, updatable = false)
    private PaymentMethod method;

    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    @Column(name = "pg_provider", length = 50)
    private String pgProvider;

    @Column(name = "pg_tid", length = 100)
    private String pgTid;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** 상태 전이 시 누적되는 도메인 이벤트. @Transient·영속 제외·{@link #pullDomainEvents}로만 노출한다. */
    @Getter(AccessLevel.NONE)
    @Transient
    private final List<Object> domainEvents = new ArrayList<>();

    @Override
    protected String getPublicIdPrefix() {
        return "pay";
    }

    /**
     * 결제 행을 생성한다(D-28). 초기 상태 PENDING이며 {@code public_id}(pay_)는 @PrePersist에서 발급된다.
     * {@code paymentAttemptKey}(pat_)는 Service 계층에서 발급해 주입한다(D-35).
     *
     * @param expiresAt PENDING 만료 시각(D-32). null이면 만료 판정에서 무한 유효로 취급된다.
     * @throws IllegalArgumentException 필수값 누락·금액 1 미만 시
     */
    public static Payment create(
            Long orderId,
            PaymentMethod method,
            Long amount,
            String paymentAttemptKey,
            LocalDateTime expiresAt) {
        if (orderId == null || method == null || amount == null
                || paymentAttemptKey == null || paymentAttemptKey.isBlank()) {
            throw new IllegalArgumentException("Payment 필수값 누락(orderId·method·amount·paymentAttemptKey).");
        }
        if (amount < 1) {
            throw new IllegalArgumentException("결제금액은 1 이상이어야 합니다. 입력: " + amount);
        }
        Payment payment = new Payment();
        payment.orderId = orderId;
        payment.method = method;
        payment.amount = amount;
        payment.paymentAttemptKey = paymentAttemptKey;
        payment.expiresAt = expiresAt;
        payment.status = PaymentStatus.PENDING;
        return payment;
    }

    /**
     * 누적된 도메인 이벤트를 읽기 전용으로 반환한다(누적 직후 검증용). 발행 후 비우려면 {@link #pullDomainEvents}를 쓴다.
     */
    public List<Object> getDomainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    /**
     * 결제 완료를 적용한다(PENDING → PAID). pgProvider·pgTid·paidAt를 설정하고 {@link PaymentCompleted}를 누적한다.
     *
     * @throws IllegalStateException PENDING이 아니어서 PAID 전이가 불가한 경우(PAY-2)
     * @throws IllegalArgumentException paidAt·pgProvider가 null인 경우
     */
    public void complete(LocalDateTime paidAt, String pgProvider, String pgTid) {
        if (paidAt == null || pgProvider == null || pgProvider.isBlank()) {
            throw new IllegalArgumentException("complete: paidAt·pgProvider는 필수입니다.");
        }
        transitionTo(PaymentStatus.PAID);
        this.paidAt = paidAt;
        this.pgProvider = pgProvider;
        this.pgTid = pgTid;
        domainEvents.add(new PaymentCompleted(id, orderId, amount, pgTid, paidAt));
    }

    /**
     * 결제 실패를 적용한다(PENDING → FAILED). failureCode를 설정하고 {@link PaymentFailed}를 누적한다.
     * CANCEL × PENDING(결제 미완료 취소)도 본 메서드로 처리한다(D-34).
     *
     * @param occurredAt 실패 통지(콜백) 시각. 이벤트 occurredAt으로 사용한다(D-34).
     * @throws IllegalStateException PENDING이 아니어서 FAILED 전이가 불가한 경우(PAY-2)
     * @throws IllegalArgumentException failureCode·occurredAt가 null·blank인 경우
     */
    public void fail(String failureCode, LocalDateTime occurredAt) {
        if (failureCode == null || failureCode.isBlank() || occurredAt == null) {
            throw new IllegalArgumentException("fail: failureCode·occurredAt는 필수입니다.");
        }
        transitionTo(PaymentStatus.FAILED);
        this.failureCode = failureCode;
        domainEvents.add(new PaymentFailed(id, orderId, failureCode, occurredAt));
    }

    /**
     * 결제 취소를 적용한다(PAID → CANCELLED). 본 트랙은 상태 전이만 처리하며, 환불 흐름·취소 이벤트는 Track 5에서 정의한다(D-34).
     *
     * @throws IllegalStateException PAID가 아니어서 CANCELLED 전이가 불가한 경우(PAY-2)
     */
    public void cancel() {
        transitionTo(PaymentStatus.CANCELLED);
    }

    /**
     * PENDING 결제의 만료 여부를 판정한다(D-32). status가 PENDING이고 expiresAt이 설정되어 있으며 now가 그 이후면 true.
     * 만료는 상태 전이 트리거가 아니다 — 새 결제 시도 차단 해제 신호로만 쓰인다(state-machine.md §1).
     */
    public boolean isExpired(LocalDateTime now) {
        if (now == null) {
            throw new IllegalArgumentException("now는 null일 수 없습니다.");
        }
        return status == PaymentStatus.PENDING && expiresAt != null && now.isAfter(expiresAt);
    }

    /**
     * 누적된 도메인 이벤트를 반환하고 내부 목록을 비운다(D-29). PaymentService가 save 직후 호출해 발행한다.
     */
    public List<Object> pullDomainEvents() {
        List<Object> pulled = new ArrayList<>(domainEvents);
        domainEvents.clear();
        return pulled;
    }

    /** 상태를 {@code next}로 전이한다. {@link PaymentStatus#canTransitionTo}로 합법성을 검증한다(PAY-2). */
    private void transitionTo(PaymentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("불법 결제 상태 전이: " + status + " → " + next);
        }
        this.status = next;
    }
}
