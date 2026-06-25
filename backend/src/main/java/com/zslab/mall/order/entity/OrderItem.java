package com.zslab.mall.order.entity;

import com.zslab.mall.common.entity.AbstractPublicIdFullAuditableEntity;
import com.zslab.mall.order.enums.OrderItemStatus;
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
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 품목(ORD 종속·ARCHIVE·public_id {@code oit_}). 주문 시점 단가·수량을 스냅샷으로 보존한다.
 *
 * <p>equals/hashCode·toString은 {@link AbstractPublicIdFullAuditableEntity}가 publicId 기준으로 제공하므로
 * 본 클래스에서 재선언하지 않는다(Q8=C·base javadoc).
 *
 * <p><b>관계</b>: {@code order}는 FK 소유측(@ManyToOne)이나 public getter를 노출하지 않는다. Aggregate 탐색은
 * Order → items 단방향이며, 연결은 {@link Order#addItem} 경유로만 이뤄진다.
 */
@Entity
@Table(name = "order_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends AbstractPublicIdFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Getter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "variant_id", nullable = false)
    private Long variantId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private Long unitPrice;

    @Column(name = "total_price", nullable = false)
    private Long totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_status", nullable = false)
    private OrderItemStatus itemStatus;

    @Override
    protected String getPublicIdPrefix() {
        return "oit";
    }

    /**
     * 주문 품목을 생성한다. 초기 상태는 {@link OrderItemStatus#ORDERED}이며 ORD-5(total = unit × quantity)를 검증한다.
     *
     * @throws IllegalArgumentException 필수값 누락·수량 1 미만·ORD-5 위반 시
     */
    public static OrderItem create(
            Long productId,
            Long variantId,
            Long sellerId,
            int quantity,
            Long unitPrice,
            Long totalPrice) {
        if (productId == null || variantId == null || sellerId == null
                || unitPrice == null || totalPrice == null) {
            throw new IllegalArgumentException("OrderItem 필수값 누락(product·variant·seller·unitPrice·totalPrice).");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("OrderItem 수량은 1 이상이어야 합니다. 입력: " + quantity);
        }
        if (totalPrice != unitPrice * quantity) {
            throw new IllegalArgumentException(
                    "ORD-5 위반: total_price(" + totalPrice + ") ≠ unit_price(" + unitPrice + ") × quantity(" + quantity + ").");
        }
        OrderItem item = new OrderItem();
        item.productId = productId;
        item.variantId = variantId;
        item.sellerId = sellerId;
        item.quantity = quantity;
        item.unitPrice = unitPrice;
        item.totalPrice = totalPrice;
        item.itemStatus = OrderItemStatus.ORDERED;
        return item;
    }

    /**
     * FK 소유측 역참조를 설정한다. {@link Order#addItem} 전용(package-private)이며 외부 직접 호출 금지.
     */
    void assignOrder(Order order) {
        this.order = order;
    }

    /**
     * 품목 상태를 {@code next}로 전이한다. {@link OrderItemStatus#canTransitionTo}로 합법성을 검증한다.
     *
     * @throws IllegalStateException 불법 전이 시
     */
    public void changeStatus(OrderItemStatus next) {
        if (next == null) {
            throw new IllegalArgumentException("전이 목표 상태는 null일 수 없습니다.");
        }
        if (!itemStatus.canTransitionTo(next)) {
            throw new IllegalStateException("불법 품목 상태 전이: " + itemStatus + " → " + next);
        }
        itemStatus = next;
    }

    /**
     * 결제 완료 처리(ORDERED → PAID). 동기화 규칙 [1]에서 {@link Order#markPaid} 경유로 호출된다.
     */
    public void markPaid() {
        changeStatus(OrderItemStatus.PAID);
    }
}
