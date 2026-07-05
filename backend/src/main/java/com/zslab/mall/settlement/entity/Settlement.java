package com.zslab.mall.settlement.entity;

import com.zslab.mall.common.entity.AbstractFullAuditableEntity;
import com.zslab.mall.settlement.enums.SettlementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 정산(STL Aggregate Root·ARCHIVE·full audit).
 *
 * <p>public_id 없음 — DDL 실측 결과 컬럼 미존재·AbstractFullAuditableEntity 적용(WARN-1·D-85).
 * sellerId(Seller Aggregate)·bankAccountId(SellerBankAccount)는 외부 Aggregate — D-01에 따라 Long 필드만(@ManyToOne 금지).
 * bankAccountId는 정산 생성 시점 계좌 스냅샷 ID(STL-3·D-85 Q1).
 * deleted_at 없음(ARCHIVE 분류) — soft-delete 미적용.
 */
@Entity
@Table(
        name = "settlement",
        indexes = {
            @Index(name = "ix_settlement_seller_status", columnList = "seller_id, status"),
            @Index(name = "ix_settlement_period", columnList = "period_start, period_end")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement extends AbstractFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "seller_id", nullable = false, updatable = false)
    private Long sellerId;

    /** 정산 생성 시점 계좌 스냅샷 ID(STL-3). 이후 계좌 변경과 무관하게 당시 계좌 추적. */
    @Column(name = "bank_account_id", nullable = false, updatable = false)
    private Long bankAccountId;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDateTime periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDateTime periodEnd;

    @Column(name = "gross_amount", nullable = false)
    private Long grossAmount;

    @Column(name = "fee_amount", nullable = false)
    private Long feeAmount;

    /** 정산 생성 시점 수수료율 스냅샷·basis-point(1000 = 10.00%). 사후 seller 율 변경과 무관하게 정산 재현성 확보. */
    @Column(name = "commission_rate", nullable = false)
    private Integer commissionRate;

    @Column(name = "refund_amount", nullable = false)
    private Long refundAmount;

    /** 정산액 = gross - fee - refund(STL-1). */
    @Column(name = "net_amount", nullable = false)
    private Long netAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SettlementStatus status;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static Settlement create(
            Long sellerId,
            Long bankAccountId,
            LocalDateTime periodStart,
            LocalDateTime periodEnd,
            Long grossAmount,
            Long feeAmount,
            Integer commissionRate,
            Long refundAmount) {
        if (sellerId == null || bankAccountId == null || periodStart == null
                || periodEnd == null || grossAmount == null || feeAmount == null
                || commissionRate == null || refundAmount == null) {
            throw new IllegalArgumentException(
                    "Settlement 필수값 누락(sellerId·bankAccountId·periodStart·periodEnd·grossAmount·feeAmount·commissionRate·refundAmount).");
        }
        Settlement settlement = new Settlement();
        settlement.sellerId = sellerId;
        settlement.bankAccountId = bankAccountId;
        settlement.periodStart = periodStart;
        settlement.periodEnd = periodEnd;
        settlement.grossAmount = grossAmount;
        settlement.feeAmount = feeAmount;
        settlement.commissionRate = commissionRate;
        settlement.refundAmount = refundAmount;
        settlement.netAmount = grossAmount - feeAmount - refundAmount;
        settlement.status = SettlementStatus.PENDING;
        return settlement;
    }

    /**
     * 정산 금액을 확정한다(PENDING → CONFIRMED·운영자 금액 확정). 전이 합법성은
     * {@link SettlementStatus#canTransitionTo}로 가드하며 위반 시 {@link IllegalStateException}을 던진다
     * (Service가 {@code SettlementInvalidStateException}(422)으로 흡수·직접 매핑 금지).
     *
     * @throws IllegalStateException 현재 상태에서 CONFIRMED 전이가 불가한 경우
     */
    public void markConfirmed() {
        if (!status.canTransitionTo(SettlementStatus.CONFIRMED)) {
            throw new IllegalStateException("불법 정산 상태 전이: " + status + " → " + SettlementStatus.CONFIRMED);
        }
        this.status = SettlementStatus.CONFIRMED;
    }

    /**
     * 지급 완료를 마킹한다(CONFIRMED → PAID·운영자 수동 마킹). 전이 성공 시 지급 시각을 채운다 —
     * STL-5(status=PAID ⟺ paid_at≠null) 불변식을 본 mutator가 강제한다(PAID 전이만 paid_at 세팅).
     * 전이 위반 시 {@link IllegalStateException}을 던진다(Service가 422로 흡수).
     *
     * @param paidAt 지급 완료 시각(Service가 now() 전달)
     * @throws IllegalArgumentException paidAt이 null인 경우(STL-5 위반 방지)
     * @throws IllegalStateException   현재 상태에서 PAID 전이가 불가한 경우
     */
    public void markPaid(LocalDateTime paidAt) {
        if (paidAt == null) {
            throw new IllegalArgumentException("지급 시각은 null일 수 없습니다.");
        }
        if (!status.canTransitionTo(SettlementStatus.PAID)) {
            throw new IllegalStateException("불법 정산 상태 전이: " + status + " → " + SettlementStatus.PAID);
        }
        this.status = SettlementStatus.PAID;
        this.paidAt = paidAt;
    }
}
