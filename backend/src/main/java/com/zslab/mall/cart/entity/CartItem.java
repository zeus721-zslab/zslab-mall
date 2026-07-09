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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 장바구니 품목(CRT Aggregate Root·HARD·full audit).
 *
 * <p>userId(User Aggregate)·variantId(Product Aggregate)는 외부 — D-01에 따라 Long 필드만(@ManyToOne 금지).
 * UK(user_id, variant_id) DDL 보장·@Table uniqueConstraints 선언 생략(D-85 Q4·DDL 신뢰).
 * deleted_at 없음(HARD 분류) — 물리 삭제.
 *
 * <p>variantPublicId(var_·CHAR(30))는 외부 계약 대상키를 cart 라인이 소유하도록 비정규화한 스냅샷이다(V17·recon-74 블로커
 * 해소). 내부 variantId FK는 enrich 조인·중복 담기 판정(UK)용으로 존치한다(이중 보유). dangling(variant soft-delete)이어도
 * 스냅샷이 있어 외부 식별자로 조회·삭제가 가능하다.
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

    // CHAR(30) 고정폭 컬럼 — @JdbcTypeCode(CHAR) 없으면 Hibernate가 VARCHAR로 매핑해 조회 불일치(LT-01·D-82).
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "variant_public_id", nullable = false, updatable = false)
    private String variantPublicId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "selected", nullable = false)
    private Boolean selected;

    /**
     * @throws IllegalArgumentException 필수값 누락 또는 quantity < 1 시
     */
    public static CartItem create(Long userId, Long variantId, String variantPublicId, Integer quantity) {
        if (userId == null || variantId == null || variantPublicId == null || quantity == null) {
            throw new IllegalArgumentException("CartItem 필수값 누락(userId·variantId·variantPublicId·quantity).");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("CartItem quantity는 1 이상이어야 합니다(CRT-2).");
        }
        CartItem cartItem = new CartItem();
        cartItem.userId = userId;
        cartItem.variantId = variantId;
        cartItem.variantPublicId = variantPublicId;
        cartItem.quantity = quantity;
        cartItem.selected = true;
        return cartItem;
    }

    /**
     * 기존 수량에 추가 수량을 누적한다(M1α·동일 variant 재담기). 저장 호출 없이 필드만 갱신하며 dirty checking으로
     * flush된다. additionalQuantity 하한(≥1) 방어는 요청 검증(@Min(1))·Service가 담당한다(create()의 CRT-2와 정합).
     */
    public void addQuantity(int additionalQuantity) {
        this.quantity += additionalQuantity;
    }

    /**
     * 수량을 절대값으로 지정한다(Track 45 수량변경). 누적하는 {@link #addQuantity}와 분리 유지한다. quantity 하한은
     * 요청 검증(@Min(1))과 함께 엔티티에서도 재검증한다(create()의 CRT-2 경계 정합·팩토리/mutator 동일 불변조건).
     *
     * @throws IllegalArgumentException newQuantity가 1 미만일 때(CRT-2)
     */
    public void changeQuantity(int newQuantity) {
        if (newQuantity < 1) {
            throw new IllegalArgumentException("CartItem quantity는 1 이상이어야 합니다(CRT-2).");
        }
        this.quantity = newQuantity;
    }

    /** 결제 대상으로 선택한다(Track 45 selected 토글). create() 기본값(true)과 정합. */
    public void select() {
        this.selected = true;
    }

    /** 결제 대상에서 해제한다(Track 45 selected 토글). CartCheckoutService의 findByUserIdAndSelectedTrue에서 제외된다. */
    public void deselect() {
        this.selected = false;
    }
}
