package com.zslab.mall.grade.entity;

import com.zslab.mall.common.entity.AbstractAggregateEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구매자 구매 집계 Read Model(ARCHIVE·집계·FK 미적용).
 *
 * <p>buyer_id는 User.id 논리 참조 — AUTO_INCREMENT 없음·이벤트 핸들러 E6에서 User.id 직접 할당.
 * updated_at은 AbstractAggregateEntity 상속·@LastModifiedDate 자동 갱신(집계 갱신 시각 추적).
 */
@Entity
@Table(name = "buyer_purchase_aggregate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuyerPurchaseAggregate extends AbstractAggregateEntity {

    @Id
    @Column(name = "buyer_id")
    @EqualsAndHashCode.Include
    private Long buyerId;

    @Column(name = "lifetime_purchase_amount", nullable = false)
    private Long lifetimePurchaseAmount;

    @Column(name = "last_ordered_at")
    private LocalDateTime lastOrderedAt;

    /**
     * 첫 구매 이벤트 핸들러 E6 호출 시 초기 레코드 생성.
     *
     * @throws IllegalArgumentException buyerId 누락 시
     */
    public static BuyerPurchaseAggregate create(Long buyerId) {
        if (buyerId == null) {
            throw new IllegalArgumentException("BuyerPurchaseAggregate 필수값 누락(buyerId).");
        }
        BuyerPurchaseAggregate aggregate = new BuyerPurchaseAggregate();
        aggregate.buyerId = buyerId;
        aggregate.lifetimePurchaseAmount = 0L;
        aggregate.lastOrderedAt = null;
        return aggregate;
    }
}
