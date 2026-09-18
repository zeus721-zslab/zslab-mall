package com.zslab.mall.settlement.entity;

import com.zslab.mall.common.entity.AbstractCreatedOnlyEntity;
import com.zslab.mall.settlement.enums.SettlementItemType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 정산 품목 스냅샷(STL 종속·ARCHIVE·Track 85). 정산 생성 시점의 품목 표시값·금액·수수료율·수수료를 박제해 사후 상품·율 변경과
 * 무관하게 재현·검수한다. settlementId·orderItemId·refundId는 Long 논리참조(D-01·@ManyToOne 금지). 불변 레코드(mutator 없음)이며
 * 재생성은 삭제 후 재적재다.
 *
 * <p>중복 적재는 DB generated 컬럼 {@code dedup_key}(settlement_id:item_type:order_item_id:COALESCE(refund_id,0)) UNIQUE가 차단한다
 * (엔티티는 매핑하지 않음·읽기 불요).
 */
@Entity
@Table(name = "settlement_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementItem extends AbstractCreatedOnlyEntity {

    private static final int BASIS_POINT_DENOMINATOR = 10_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private Long settlementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, updatable = false)
    private SettlementItemType itemType;

    @Column(name = "order_item_id", nullable = false, updatable = false)
    private Long orderItemId;

    /** REFUND만 채움(SALE은 NULL). */
    @Column(name = "refund_id", updatable = false)
    private Long refundId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "order_public_id", nullable = false, updatable = false, length = 30)
    private String orderPublicId;

    @Column(name = "product_name", nullable = false, updatable = false, length = 200)
    private String productName;

    @Column(name = "option_label", updatable = false, length = 500)
    private String optionLabel;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    /** SALE = order_item.total_price · REFUND = refund.amount. */
    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    /** 적용 수수료율 스냅샷·basis-point(order_item.commission_rate). */
    @Column(name = "commission_rate", nullable = false, updatable = false)
    private Integer commissionRate;

    /** SALE = floor(amount × rate / 10000) · REFUND = 0(환불 시 수수료 환급 없음·현행 정책). */
    @Column(name = "fee_amount", nullable = false, updatable = false)
    private Long feeAmount;

    /** SALE = order_item.confirmed_at · REFUND = refund.refunded_at. */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    /** 품목별 수수료 = floor(금액 × 율 / 10000). 정산 fee는 품목 수수료의 합이다(합계 버림이 아님·Track 85 확정). */
    public static long calculateFee(long amount, int commissionRate) {
        return amount * commissionRate / BASIS_POINT_DENOMINATOR;
    }

    /**
     * 구매확정 매출 품목 스냅샷을 만든다. feeAmount는 {@link #calculateFee}로 산정한다.
     *
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static SettlementItem sale(Long settlementId, Long orderItemId, String orderPublicId, String productName,
            String optionLabel, int quantity, Long amount, Integer commissionRate, LocalDateTime confirmedAt) {
        SettlementItem item = base(settlementId, SettlementItemType.SALE, orderItemId, orderPublicId, productName,
                optionLabel, quantity, amount, commissionRate, confirmedAt);
        item.feeAmount = calculateFee(amount, commissionRate);
        return item;
    }

    /**
     * 완료 환불 차감 품목 스냅샷을 만든다. 수수료 환급이 없으므로 feeAmount=0이며 commissionRate는 참고 스냅샷이다.
     *
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static SettlementItem refund(Long settlementId, Long orderItemId, Long refundId, String orderPublicId,
            String productName, String optionLabel, int quantity, Long amount, Integer commissionRate,
            LocalDateTime refundedAt) {
        if (refundId == null) {
            throw new IllegalArgumentException("SettlementItem(REFUND) refundId 누락.");
        }
        SettlementItem item = base(settlementId, SettlementItemType.REFUND, orderItemId, orderPublicId, productName,
                optionLabel, quantity, amount, commissionRate, refundedAt);
        item.refundId = refundId;
        item.feeAmount = 0L;
        return item;
    }

    private static SettlementItem base(Long settlementId, SettlementItemType itemType, Long orderItemId,
            String orderPublicId, String productName, String optionLabel, int quantity, Long amount,
            Integer commissionRate, LocalDateTime occurredAt) {
        if (settlementId == null || orderItemId == null || orderPublicId == null || productName == null
                || amount == null || commissionRate == null || occurredAt == null) {
            throw new IllegalArgumentException(
                    "SettlementItem 필수값 누락(settlementId·orderItemId·orderPublicId·productName·amount·commissionRate·occurredAt).");
        }
        SettlementItem item = new SettlementItem();
        item.settlementId = settlementId;
        item.itemType = itemType;
        item.orderItemId = orderItemId;
        item.orderPublicId = orderPublicId;
        item.productName = productName;
        item.optionLabel = optionLabel;
        item.quantity = quantity;
        item.amount = amount;
        item.commissionRate = commissionRate;
        item.occurredAt = occurredAt;
        return item;
    }
}
