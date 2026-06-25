package com.zslab.mall.order.entity;

import com.zslab.mall.common.entity.AbstractPublicIdFullAuditableEntity;
import com.zslab.mall.order.enums.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문(ORD Aggregate Root·ARCHIVE·public_id {@code ord_}).
 *
 * <p>Aggregate 포함: {@link OrderItem}(1:N)·{@link OrderShippingSnapshot}(1:1). 외부 ID 참조는 buyer_id(User.id).
 *
 * <p>equals/hashCode·toString은 {@link AbstractPublicIdFullAuditableEntity}가 publicId 기준으로 제공하므로
 * 본 클래스에서 재선언하지 않는다(Q8=C·base javadoc).
 *
 * <p><b>상태(ORD-2)</b>: status는 OrderItem 집계 캐시다. 결제 완료 시 {@link #markPaid}로 직접 PAID 적용(규칙 [1]),
 * 그 외 OrderItem 변경 후에는 OrderStatusResolver 결과를 {@link #applyResolvedStatus}로 반영한다.
 *
 * <p><b>total_price 규약</b>: 본 트랙에서 total_price는 포함 OrderItem total_price의 합으로 정의한다
 * (discount_amount·shipping_fee는 별도 컬럼). {@link #addItem} 시 누적된다.
 */
@Entity
@Table(name = "`order`")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends AbstractPublicIdFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "order_no", nullable = false, length = 50, updatable = false)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @Column(name = "total_price", nullable = false)
    private Long totalPrice;

    @Column(name = "discount_amount", nullable = false)
    private Long discountAmount;

    @Column(name = "shipping_fee", nullable = false)
    private Long shippingFee;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "ordered_at")
    private LocalDateTime orderedAt;

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "order", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    private final List<OrderItem> items = new ArrayList<>();

    @OneToOne(mappedBy = "order", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private OrderShippingSnapshot shippingSnapshot;

    @Override
    protected String getPublicIdPrefix() {
        return "ord";
    }

    /**
     * 주문 골격을 생성한다. 초기 상태 PENDING_PAYMENT·total_price 0. OrderItem은 {@link #addItem}으로 추가한다.
     *
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static Order create(Long buyerId, String orderNo, Long discountAmount, Long shippingFee) {
        if (buyerId == null || orderNo == null || orderNo.isBlank()
                || discountAmount == null || shippingFee == null) {
            throw new IllegalArgumentException("Order 필수값 누락(buyerId·orderNo·discountAmount·shippingFee).");
        }
        Order order = new Order();
        order.buyerId = buyerId;
        order.orderNo = orderNo;
        order.status = OrderStatus.PENDING_PAYMENT;
        order.totalPrice = 0L;
        order.discountAmount = discountAmount;
        order.shippingFee = shippingFee;
        return order;
    }

    /**
     * 포함 OrderItem 목록을 읽기 전용으로 반환한다. 추가는 {@link #addItem}으로만 한다(Aggregate 무결성).
     */
    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    /**
     * 주문 품목을 Aggregate에 추가한다. 양측 연결을 설정하고 total_price를 누적한다.
     */
    public void addItem(OrderItem item) {
        if (item == null) {
            throw new IllegalArgumentException("추가할 OrderItem은 null일 수 없습니다.");
        }
        items.add(item);
        item.assignOrder(this);
        totalPrice += item.getTotalPrice();
    }

    /**
     * 배송지 스냅샷을 연결한다(1:1·QB-10 A'-1). 양측 연결을 설정하며 직접 setter는 노출하지 않는다.
     */
    public void attachSnapshot(OrderShippingSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("연결할 OrderShippingSnapshot은 null일 수 없습니다.");
        }
        this.shippingSnapshot = snapshot;
        snapshot.assignOrder(this);
    }

    /**
     * 결제 완료를 적용한다(동기화 규칙 [1]). 모든 OrderItem을 PAID로 전이하고 status=PAID·paid_at을 갱신한다.
     * Resolver를 경유하지 않는다.
     *
     * @throws IllegalStateException OrderItem이 ORDERED가 아니어서 PAID 전이 불가 시
     */
    public void markPaid(LocalDateTime paidAt) {
        if (paidAt == null) {
            throw new IllegalArgumentException("paidAt은 null일 수 없습니다.");
        }
        for (OrderItem item : items) {
            item.markPaid();
        }
        this.status = OrderStatus.PAID;
        this.paidAt = paidAt;
    }

    /**
     * OrderStatusResolver가 산출한 status를 반영한다(ORD-2). 전이 검증은 Resolver가 단일 책임으로 보장한다.
     */
    public void applyResolvedStatus(OrderStatus resolvedStatus) {
        if (resolvedStatus == null) {
            throw new IllegalArgumentException("resolvedStatus는 null일 수 없습니다.");
        }
        this.status = resolvedStatus;
    }
}
