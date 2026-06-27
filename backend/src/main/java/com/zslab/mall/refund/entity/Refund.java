package com.zslab.mall.refund.entity;

import com.zslab.mall.common.entity.AbstractPublicIdFullAuditableEntity;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.refund.event.RefundCompleted;
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

/**
 * 환불(CLM 종속·ARCHIVE·public_id {@code rfn_}). Claim Aggregate 소속이며 Root가 아니다(aggregate-boundary §2.5·CLM-3).
 *
 * <p>Claim·Payment는 {@code claimId}·{@code paymentId}(각 id)로 외부 참조하며 JPA 연관(@ManyToOne)을 두지 않는다(ID 참조).
 *
 * <p><b>상태(RFN-2)</b>: status 전이는 {@link RefundStatus#canTransitionTo}가 단일 책임으로 강제한다(COMPLETED·FAILED 불가역).
 *
 * <p><b>pg_refund_id 생명주기(expected-spec §6·webhook 매칭 키)</b>: PG 환불 요청 등록 시점({@code RefundService.initiate}의
 * {@code gateway.refund()} 응답)에 {@link #assignPgRefundId}로 PENDING 행에 부여한다. 이후 webhook 콜백이 동일 pg_refund_id로
 * 행을 매칭해 COMPLETED 전이를 구동한다(RFN-1·RFN-3 멱등 키). COMPLETED 전이 시 pg_refund_id NOT NULL이 보장된다(RFN-1).
 *
 * <p><b>refunded_at(D-70)</b>: COMPLETED 전이 시점의 <b>시스템 시각</b>이다. PG 원시 시각(승인·콜백 수신)이 아니며
 * {@code RefundService}가 {@code LocalDateTime.now()}를 {@link #markCompleted}에 주입한다.
 *
 * <p><b>이벤트(D-29)</b>: {@link #markCompleted}가 {@link RefundCompleted}를 내부 목록에 누적하며,
 * {@link #pullDomainEvents}로 꺼낸 뒤 {@code RefundService}가 save 직후 발행한다.
 */
@Entity
@Table(name = "refund")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends AbstractPublicIdFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RefundStatus status;

    @Column(name = "pg_refund_id", length = 100)
    private String pgRefundId;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    /** 상태 전이 시 누적되는 도메인 이벤트. @Transient·영속 제외·{@link #pullDomainEvents}로만 노출한다. */
    @Getter(AccessLevel.NONE)
    @Transient
    private final List<Object> domainEvents = new ArrayList<>();

    @Override
    protected String getPublicIdPrefix() {
        return "rfn";
    }

    /**
     * 환불 행을 생성한다. 초기 상태 PENDING이며 pg_refund_id·refunded_at는 미설정이다. public_id(rfn_)는 @PrePersist에서 발급된다.
     *
     * @throws IllegalArgumentException 필수값 누락·금액 1 미만 시
     */
    public static Refund create(Long claimId, Long paymentId, Long amount) {
        if (claimId == null || paymentId == null || amount == null) {
            throw new IllegalArgumentException("Refund 필수값 누락(claimId·paymentId·amount).");
        }
        if (amount < 1) {
            throw new IllegalArgumentException("환불금액은 1 이상이어야 합니다. 입력: " + amount);
        }
        Refund refund = new Refund();
        refund.claimId = claimId;
        refund.paymentId = paymentId;
        refund.amount = amount;
        refund.status = RefundStatus.PENDING;
        return refund;
    }

    /** 누적된 도메인 이벤트를 읽기 전용으로 반환한다(누적 직후 검증용). 발행 후 비우려면 {@link #pullDomainEvents}를 쓴다. */
    public List<Object> getDomainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    /**
     * PG 환불 요청 등록 결과로 받은 pg_refund_id를 PENDING 행에 부여한다(expected-spec §6·webhook 매칭 키).
     *
     * @throws IllegalStateException PENDING이 아닌 상태에서 부여를 시도한 경우
     * @throws IllegalArgumentException pgRefundId가 null·blank인 경우
     */
    public void assignPgRefundId(String pgRefundId) {
        if (pgRefundId == null || pgRefundId.isBlank()) {
            throw new IllegalArgumentException("assignPgRefundId: pgRefundId는 필수입니다.");
        }
        if (status != RefundStatus.PENDING) {
            throw new IllegalStateException("pg_refund_id 부여는 PENDING 상태에서만 가능합니다. 현재: " + status);
        }
        this.pgRefundId = pgRefundId;
    }

    /**
     * 환불 완료를 적용한다(PENDING → COMPLETED·불가역). refunded_at을 시스템 시각으로 채우고 {@link RefundCompleted}를 누적한다.
     *
     * @param refundedAt COMPLETED 전이 시스템 시각(D-70)·PG 원시 시각 아님
     * @throws IllegalStateException PENDING이 아니거나 pg_refund_id가 없는 경우(RFN-1·RFN-2)
     * @throws IllegalArgumentException refundedAt가 null인 경우
     */
    public void markCompleted(LocalDateTime refundedAt) {
        if (refundedAt == null) {
            throw new IllegalArgumentException("markCompleted: refundedAt는 필수입니다.");
        }
        // RFN-1 방어선: pg_refund_id 없이 COMPLETED 전이 금지(주 가드는 RefundService·webhook 콜백 키)
        if (pgRefundId == null || pgRefundId.isBlank()) {
            throw new IllegalStateException("RFN-1 위반: pg_refund_id 없이 COMPLETED 전이 불가.");
        }
        transitionTo(RefundStatus.COMPLETED);
        this.refundedAt = refundedAt;
        domainEvents.add(new RefundCompleted(id, claimId, paymentId, amount, refundedAt));
    }

    /**
     * 환불 실패를 적용한다(PENDING → FAILED·불가역). 콜백 실패와 PG 호출 예외를 모두 포함한다(D-67·CR-03).
     *
     * <p>실패 사유는 별도 컬럼(failure_reason)을 두지 않으며(CR-01 보류) Service 로깅으로만 남긴다.
     *
     * @throws IllegalStateException PENDING이 아니어서 FAILED 전이가 불가한 경우(RFN-2)
     */
    public void markFailed() {
        transitionTo(RefundStatus.FAILED);
    }

    /** 누적된 도메인 이벤트를 반환하고 내부 목록을 비운다(D-29). RefundService가 save 직후 호출해 발행한다. */
    public List<Object> pullDomainEvents() {
        List<Object> pulled = new ArrayList<>(domainEvents);
        domainEvents.clear();
        return pulled;
    }

    /** 상태를 {@code next}로 전이한다. {@link RefundStatus#canTransitionTo}로 합법성을 검증한다(RFN-2). */
    private void transitionTo(RefundStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("불법 환불 상태 전이: " + status + " → " + next);
        }
        this.status = next;
    }
}
