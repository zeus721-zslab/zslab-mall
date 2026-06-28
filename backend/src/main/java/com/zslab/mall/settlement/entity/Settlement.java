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
            Long refundAmount) {
        if (sellerId == null || bankAccountId == null || periodStart == null
                || periodEnd == null || grossAmount == null || feeAmount == null
                || refundAmount == null) {
            throw new IllegalArgumentException(
                    "Settlement 필수값 누락(sellerId·bankAccountId·periodStart·periodEnd·grossAmount·feeAmount·refundAmount).");
        }
        Settlement settlement = new Settlement();
        settlement.sellerId = sellerId;
        settlement.bankAccountId = bankAccountId;
        settlement.periodStart = periodStart;
        settlement.periodEnd = periodEnd;
        settlement.grossAmount = grossAmount;
        settlement.feeAmount = feeAmount;
        settlement.refundAmount = refundAmount;
        settlement.netAmount = grossAmount - feeAmount - refundAmount;
        settlement.status = SettlementStatus.PENDING;
        return settlement;
    }
}
