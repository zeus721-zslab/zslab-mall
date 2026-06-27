package com.zslab.mall.inventory.entity;

import com.zslab.mall.common.entity.AbstractFullAuditableEntity;
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
 * 재고(INV Aggregate Root·variant 1:1). <b>Track 4 read-only</b>(D-57) — 재결제 재검증(D-51·D-60)의
 * 가용 재고 조회 전용으로 최소 신설한다. 재고 차감·증가·InventoryHistory 등 쓰기 책임은 Track 7로 이연한다.
 *
 * <p>public_id 미부여 표준 id 엔티티이며 {@link AbstractFullAuditableEntity}를 상속한다(audit-policy.md full·V1 DDL L488).
 * read-only 원칙에 따라 정적 팩토리·setter·도메인 행위(쓰기 메서드)를 두지 않는다. 가용 재고 판정은 호출 Service가 수행한다.
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
}
