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
import java.time.LocalDate;
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
 * bankAccountId는 지급(PAID) 시점 주 계좌 스냅샷 ID(STL-3·Track 85 — 생성 시 NULL·지급 시 1회 설정).
 * deleted_at 없음(ARCHIVE 분류) — soft-delete 미적용.
 *
 * <p><b>Track 85</b>: 금액(gross·fee·refund)은 {@code settlement_item} 품목 스냅샷의 합이며 수수료율은 품목별
 * {@code order_item.commission_rate} 스냅샷이 SoT다. 헤더 {@code commissionRate}는 구(Track 48) 컬럼으로 신규 행은 NULL(DROP 이월).
 * scheduledPayDate = 기간 말일 + N일(설정)로 생성 시 계산한다.
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

    /** 지급(PAID) 시점 주 계좌 스냅샷 ID(STL-3). 생성 시 NULL이며 {@link #markPaid}가 1회 설정한다. */
    @Column(name = "bank_account_id")
    private Long bankAccountId;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDateTime periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDateTime periodEnd;

    @Column(name = "gross_amount", nullable = false)
    private Long grossAmount;

    @Column(name = "fee_amount", nullable = false)
    private Long feeAmount;

    /** (구·Track 48) 헤더 수수료율 스냅샷. Track 85부터 품목별 율이 SoT라 신규 행은 NULL·응답 미노출(DROP 이월). */
    @Column(name = "commission_rate", updatable = false)
    private Integer commissionRate;

    @Column(name = "refund_amount", nullable = false)
    private Long refundAmount;

    /** 이월 차감 합(CARRYOVER 품목 금액 합·앞선 음수 정산의 부족분·Track 104-3b). */
    @Column(name = "carryover_amount", nullable = false)
    private Long carryoverAmount;

    /** 정산액 = gross - fee - refund - carryover(STL-1). */
    @Column(name = "net_amount", nullable = false)
    private Long netAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SettlementStatus status;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** 지급예정일 = 기간 말일 + N일(settlement.payout-offset-days). 생성 시 계산·불변. */
    @Column(name = "scheduled_pay_date", updatable = false)
    private LocalDate scheduledPayDate;

    /**
     * 정산 헤더를 생성한다(PENDING·계좌 NULL·헤더 율 NULL). 금액은 호출부가 품목 스냅샷에서 합산해 전달한다(Track 85).
     *
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static Settlement create(
            Long sellerId,
            LocalDateTime periodStart,
            LocalDateTime periodEnd,
            Long grossAmount,
            Long feeAmount,
            Long refundAmount,
            Long carryoverAmount,
            LocalDate scheduledPayDate) {
        if (sellerId == null || periodStart == null || periodEnd == null || grossAmount == null
                || feeAmount == null || refundAmount == null || carryoverAmount == null || scheduledPayDate == null) {
            throw new IllegalArgumentException("Settlement 필수값 누락(sellerId·periodStart·periodEnd·grossAmount·feeAmount·"
                    + "refundAmount·carryoverAmount·scheduledPayDate).");
        }
        Settlement settlement = new Settlement();
        settlement.sellerId = sellerId;
        settlement.periodStart = periodStart;
        settlement.periodEnd = periodEnd;
        settlement.grossAmount = grossAmount;
        settlement.feeAmount = feeAmount;
        settlement.refundAmount = refundAmount;
        settlement.carryoverAmount = carryoverAmount;
        settlement.netAmount = grossAmount - feeAmount - refundAmount - carryoverAmount;
        settlement.scheduledPayDate = scheduledPayDate;
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
     * 지급 완료를 마킹한다(CONFIRMED → PAID·운영자 수동 마킹). 전이 성공 시 지급 시각과 지급 계좌 스냅샷을 채운다 —
     * STL-5(status=PAID ⟺ paid_at≠null) 불변식을 본 mutator가 강제한다(PAID 전이만 paid_at 세팅). 계좌 스냅샷(STL-3)은
     * 지급 시점 주 계좌이며 이후 계좌 변경과 무관하게 당시 계좌를 추적한다. 전이 위반 시 {@link IllegalStateException}을
     * 던진다(Service가 422로 흡수). net 음수·계좌 부재 판정은 Service 책임이다.
     *
     * @param paidAt        지급 완료 시각(Service가 now() 전달)
     * @param bankAccountId 지급 계좌(주 계좌) id
     * @throws IllegalArgumentException paidAt·bankAccountId가 null인 경우
     * @throws IllegalStateException    현재 상태에서 PAID 전이가 불가한 경우
     */
    public void markPaid(LocalDateTime paidAt, Long bankAccountId) {
        if (paidAt == null) {
            throw new IllegalArgumentException("지급 시각은 null일 수 없습니다.");
        }
        if (bankAccountId == null) {
            throw new IllegalArgumentException("지급 계좌는 null일 수 없습니다.");
        }
        if (!status.canTransitionTo(SettlementStatus.PAID)) {
            throw new IllegalStateException("불법 정산 상태 전이: " + status + " → " + SettlementStatus.PAID);
        }
        this.status = SettlementStatus.PAID;
        this.paidAt = paidAt;
        this.bankAccountId = bankAccountId;
    }
}
