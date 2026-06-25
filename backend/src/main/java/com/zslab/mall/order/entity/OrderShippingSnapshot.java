package com.zslab.mall.order.entity;

import com.zslab.mall.common.entity.AbstractFullAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 주문 배송지 스냅샷(ORD 종속·ARCHIVE). 주문 시점 배송지를 불변 보존한다.
 *
 * <p>public_id 미부여 표준 id 엔티티이며 {@link AbstractFullAuditableEntity}를 상속한다(db-schema §1.1).
 *
 * <p><b>관계(QB-10 A'-1)</b>: 본 엔티티가 FK({@code order_id}) 소유측이다. Order → Snapshot 단방향 탐색만 허용하며
 * Snapshot → Order 역참조는 내부 보유·비공개다. {@code order} 필드는 public getter를 노출하지 않고
 * ({@code @Getter(AccessLevel.NONE)}), FK 연결은 {@link com.zslab.mall.order.entity.Order#attachSnapshot} 경유로만 이뤄진다.
 */
@Entity
@Table(name = "order_shipping_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class OrderShippingSnapshot extends AbstractFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    @ToString.Include
    private Long id;

    @Getter(AccessLevel.NONE)
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(name = "recipient_name", nullable = false, length = 50)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String recipientPhone;

    @Column(name = "zonecode", nullable = false, length = 10)
    private String zonecode;

    @Column(name = "address_road", nullable = false, length = 200)
    private String addressRoad;

    @Column(name = "address_jibun", length = 200)
    private String addressJibun;

    @Column(name = "address_detail", length = 200)
    private String addressDetail;

    @Column(name = "delivery_memo", columnDefinition = "TEXT")
    private String deliveryMemo;

    /**
     * 배송지 데이터로 스냅샷을 생성한다. FK 연결은 {@link Order#attachSnapshot}이 담당한다.
     */
    public static OrderShippingSnapshot create(
            String recipientName,
            String recipientPhone,
            String zonecode,
            String addressRoad,
            String addressJibun,
            String addressDetail,
            String deliveryMemo) {
        OrderShippingSnapshot snapshot = new OrderShippingSnapshot();
        snapshot.recipientName = recipientName;
        snapshot.recipientPhone = recipientPhone;
        snapshot.zonecode = zonecode;
        snapshot.addressRoad = addressRoad;
        snapshot.addressJibun = addressJibun;
        snapshot.addressDetail = addressDetail;
        snapshot.deliveryMemo = deliveryMemo;
        return snapshot;
    }

    /**
     * FK 소유측 역참조를 설정한다. {@link Order#attachSnapshot} 전용(package-private)이며 외부 직접 호출 금지.
     */
    void assignOrder(Order order) {
        this.order = order;
    }

    /**
     * 본 스냅샷이 주어진 주문에 속하는지 판정한다(역참조 getter 미노출 대체 도메인 메서드·QB-10).
     *
     * <p>현재 직접 사용처 없음·향후 Order 외부에서 Snapshot 귀속 검증이 필요한 시점에 활용(보조 메서드).
     */
    public boolean belongsTo(Long orderId) {
        return order != null && orderId != null && orderId.equals(order.getId());
    }
}
