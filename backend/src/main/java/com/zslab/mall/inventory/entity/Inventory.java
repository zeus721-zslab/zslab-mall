package com.zslab.mall.inventory.entity;

import com.zslab.mall.common.entity.AbstractFullAuditableEntity;
import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 재고(INV Aggregate Root·variant 1:1). <b>Track 17 D-101 §2 도메인 행위 4건 구현</b> — 예약(reserve)·해제(release)·
 * 차감(commitReservation)·복구(restoreStock)를 Aggregate 내부에 캡슐화한다. 동시성은 비관적 락으로 일원화하며
 * (D-101 §4 α·@Version 미도입), quantity_available 재계산은 도메인 내부 {@link #recalculateAvailable()}가 전담한다.
 *
 * <p>public_id 미부여 표준 id 엔티티이며 {@link AbstractFullAuditableEntity}를 상속한다(audit-policy.md full·V1 DDL L488).
 * on_hand·reserved·available 3필드는 도메인 행위 내부에서만 mutate하며 setter를 두지 않는다(D-101 §2 캡슐화).
 *
 * <p><b>equals/hashCode 가이드(Q8=C)</b>: 표준 id 엔티티이므로 id 필드에 {@code @EqualsAndHashCode.Include}를 명시한다
 * ({@link com.zslab.mall.order.entity.OrderShippingSnapshot} 동일 패턴).
 */
@Entity
@Table(name = "inventory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class Inventory extends AbstractFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    @ToString.Include
    private Long id;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private Long variantId;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @Column(name = "quantity_reserved", nullable = false)
    private int quantityReserved;

    @Column(name = "quantity_available", nullable = false)
    private int quantityAvailable;

    /**
     * 재고를 예약한다(E1 OrderPlaced). reserved += qty 후 available를 재계산하며, 예약 결과 available &lt; 0이면
     * oversell이므로 INV-1 위반으로 차단한다(D-101 §2·§10 β). 계산·검증 후 mutate하여 위반 시 상태를 보존한다.
     *
     * @throws IllegalArgumentException qty가 양수가 아닐 때
     * @throws InventoryInvariantViolationException 예약 결과 가용 재고가 음수일 때(INV-1)
     */
    public void reserve(int qty) {
        requirePositiveQty(qty, "reserve");
        int projectedReserved = quantityReserved + qty;
        int projectedAvailable = quantityOnHand - projectedReserved;
        if (projectedAvailable < 0) {
            throw new InventoryInvariantViolationException(
                    "불법 재고 예약: variantId=" + variantId + ", 요청=" + qty
                            + ", 가용=" + quantityAvailable + ", 예약후가용=" + projectedAvailable);
        }
        quantityReserved = projectedReserved;
        recalculateAvailable();
    }

    /**
     * 재고 예약을 해제한다(E3 PaymentFailed). reserved -= qty 후 available를 재계산한다. 예약 초과 해제는
     * INV-3 위반으로 차단한다(D-101 §2).
     *
     * @throws IllegalArgumentException qty가 양수가 아닐 때
     * @throws InventoryInvariantViolationException 해제량이 예약량을 초과할 때(INV-3)
     */
    public void release(int qty) {
        requirePositiveQty(qty, "release");
        if (quantityReserved - qty < 0) {
            throw new InventoryInvariantViolationException(
                    "불법 재고 해제: variantId=" + variantId + ", 요청=" + qty + ", 예약=" + quantityReserved);
        }
        quantityReserved -= qty;
        recalculateAvailable();
    }

    /**
     * 예약분을 실물 차감으로 확정한다(E2 PaymentCompleted). on_hand·reserved를 동시에 qty만큼 줄이고 available를
     * 재계산한다. 예약 부족(INV-3)·실물 부족(INV-4)은 각각 차단한다(D-101 §2·D-100 Q1 상태전이 자연 흡수).
     *
     * @throws IllegalArgumentException qty가 양수가 아닐 때
     * @throws InventoryInvariantViolationException 예약 부족(INV-3) 또는 실물 부족(INV-4)일 때
     */
    public void commitReservation(int qty) {
        requirePositiveQty(qty, "commitReservation");
        if (quantityReserved - qty < 0) {
            throw new InventoryInvariantViolationException(
                    "불법 재고 차감·예약 부족: variantId=" + variantId + ", 요청=" + qty + ", 예약=" + quantityReserved);
        }
        if (quantityOnHand - qty < 0) {
            throw new InventoryInvariantViolationException(
                    "불법 재고 차감·실물 부족: variantId=" + variantId + ", 요청=" + qty + ", 실물=" + quantityOnHand);
        }
        quantityOnHand -= qty;
        quantityReserved -= qty;
        recalculateAvailable();
    }

    /**
     * 실물 재고를 복구한다(E9 ClaimCompleted·CANCEL 결제후취소·RETURN 반품/교환회수). on_hand += qty 후 available를
     * 재계산한다. 증가 방향이므로 INV-1·INV-4는 자연 정합한다(D-101 §2·D-08 M-12).
     *
     * @throws IllegalArgumentException qty가 양수가 아닐 때
     */
    public void restoreStock(int qty) {
        requirePositiveQty(qty, "restoreStock");
        quantityOnHand += qty;
        recalculateAvailable();
    }

    /**
     * 운영자 수동 재고를 조정한다(Track 21 D-105 §4·InventoryHistoryChangeType.ADJUST). quantityDelta만큼 on_hand를
     * 증감한 뒤 available를 재계산한다. 감소(delta&lt;0) 시 실물 부족(INV-4)·가용 음수(INV-1)를 각각 차단하며, delta=0은
     * 무의미 조정이므로 거부한다. 증가(delta&gt;0)는 {@link #restoreStock}과 동일하게 INV-1·INV-4가 자연 정합한다.
     * 계산·검증 후 mutate하여 위반 시 상태를 보존한다({@link #reserve} 패턴 정합).
     *
     * @throws IllegalArgumentException quantityDelta가 0일 때
     * @throws InventoryInvariantViolationException 조정 결과 실물(INV-4) 또는 가용(INV-1)이 음수일 때
     */
    public void adjustStock(int quantityDelta) {
        if (quantityDelta == 0) {
            throw new IllegalArgumentException("adjustStock: quantityDelta는 0이 아니어야 합니다.");
        }
        int projectedOnHand = quantityOnHand + quantityDelta;
        if (projectedOnHand < 0) {
            throw new InventoryInvariantViolationException(
                    "불법 재고 조정·실물 부족: variantId=" + variantId + ", delta=" + quantityDelta
                            + ", 실물=" + quantityOnHand);
        }
        int projectedAvailable = projectedOnHand - quantityReserved;
        if (projectedAvailable < 0) {
            throw new InventoryInvariantViolationException(
                    "불법 재고 조정·가용 부족: variantId=" + variantId + ", delta=" + quantityDelta
                            + ", 가용=" + quantityAvailable + ", 조정후가용=" + projectedAvailable);
        }
        quantityOnHand = projectedOnHand;
        recalculateAvailable();
    }

    /** available = on_hand - reserved. 가용 재고 재계산은 도메인 내부가 전담한다(D-101 §2 캡슐화). */
    private void recalculateAvailable() {
        quantityAvailable = quantityOnHand - quantityReserved;
    }

    private void requirePositiveQty(int qty, String action) {
        if (qty <= 0) {
            throw new IllegalArgumentException(action + ": qty는 양수여야 합니다. qty=" + qty);
        }
    }
}
