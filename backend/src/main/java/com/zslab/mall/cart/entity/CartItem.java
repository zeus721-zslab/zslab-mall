package com.zslab.mall.cart.entity;

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

/**
 * 장바구니 품목(CRT Aggregate Root·HARD·full audit).
 *
 * <p>userId(User Aggregate)·variantId(Product Aggregate)는 외부 — D-01에 따라 Long 필드만(@ManyToOne 금지).
 * UK(user_id, variant_id) DDL 보장·@Table uniqueConstraints 선언 생략(D-85 Q4·DDL 신뢰).
 * deleted_at 없음(HARD 분류) — 물리 삭제.
 */
@Entity
@Table(name = "cart_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem extends AbstractFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "variant_id", nullable = false)
    private Long variantId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "selected", nullable = false)
    private Boolean selected;

    /**
     * @throws IllegalArgumentException 필수값 누락 또는 quantity < 1 시
     */
    public static CartItem create(Long userId, Long variantId, Integer quantity) {
        if (userId == null || variantId == null || quantity == null) {
            throw new IllegalArgumentException("CartItem 필수값 누락(userId·variantId·quantity).");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("CartItem quantity는 1 이상이어야 합니다(CRT-2).");
        }
        CartItem cartItem = new CartItem();
        cartItem.userId = userId;
        cartItem.variantId = variantId;
        cartItem.quantity = quantity;
        cartItem.selected = true;
        return cartItem;
    }
}
