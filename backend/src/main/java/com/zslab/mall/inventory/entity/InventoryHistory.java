package com.zslab.mall.inventory.entity;

import com.zslab.mall.common.entity.AbstractCreatedOnlyEntity;
import com.zslab.mall.inventory.enums.InventoryHistoryChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 재고 변동 이력(INV 종속·ARCHIVE·append-only).
 *
 * <p>inventory는 Inventory Aggregate 내부 — @ManyToOne LAZY 허용.
 * referenceId는 polymorphic 논리 참조 — FK 없음·D분류·앱 검증.
 * append-only이지만 @Immutable은 사용하지 않는다(A2 결정·AbstractCreatedOnlyEntity Javadoc 정합).
 */
@Entity
@Table(name = "inventory_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryHistory extends AbstractCreatedOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_id", nullable = false, updatable = false)
    private Inventory inventory;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, updatable = false)
    private InventoryHistoryChangeType changeType;

    @Column(name = "quantity_delta", nullable = false, updatable = false)
    private int quantityDelta;

    @Column(name = "reference_type", nullable = false, length = 50, updatable = false)
    private String referenceType;

    @Column(name = "reference_id", updatable = false)
    private Long referenceId;

    @Column(name = "reason", length = 255, updatable = false)
    private String reason;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static InventoryHistory create(
            Inventory inventory,
            InventoryHistoryChangeType changeType,
            int quantityDelta,
            String referenceType,
            Long referenceId,
            String reason) {
        if (inventory == null || changeType == null || referenceType == null || referenceType.isBlank()) {
            throw new IllegalArgumentException("InventoryHistory 필수값 누락(inventory·changeType·referenceType).");
        }
        InventoryHistory history = new InventoryHistory();
        history.inventory = inventory;
        history.changeType = changeType;
        history.quantityDelta = quantityDelta;
        history.referenceType = referenceType;
        history.referenceId = referenceId;
        history.reason = reason;
        return history;
    }
}
