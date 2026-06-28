package com.zslab.mall.delivery.entity;

import com.zslab.mall.common.entity.AbstractPublicIdFullAuditableEntity;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryStatus;
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

/**
 * 배송(DLV Aggregate Root·ARCHIVE·public_id {@code dlv_}).
 *
 * <p>orderItemId(Order Aggregate)는 외부 — D-01에 따라 Long 필드만(@ManyToOne 금지).
 * trackingNo는 nullable·UK(DLV-1)·MariaDB UNIQUE KEY에서 NULL 다건 허용(DLV-1).
 * deleted_at 없음(ARCHIVE 분류) — soft-delete 미적용.
 *
 * <p>equals/hashCode·toString은 {@link AbstractPublicIdFullAuditableEntity}가 publicId 기준으로 제공한다.
 */
@Entity
@Table(name = "delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Delivery extends AbstractPublicIdFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_item_id", nullable = false, updatable = false)
    private Long orderItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "carrier", nullable = false)
    private DeliveryCarrier carrier;

    /** 발송 전 NULL 허용. MariaDB UNIQUE KEY에서 NULL은 비교 제외 — 다건 NULL 삽입 허용(DLV-1). */
    @Column(name = "tracking_no", length = 100)
    private String trackingNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DeliveryStatus status;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Override
    protected String getPublicIdPrefix() {
        return "dlv";
    }

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static Delivery create(Long orderItemId, DeliveryCarrier carrier) {
        if (orderItemId == null || carrier == null) {
            throw new IllegalArgumentException("Delivery 필수값 누락(orderItemId·carrier).");
        }
        Delivery delivery = new Delivery();
        delivery.orderItemId = orderItemId;
        delivery.carrier = carrier;
        delivery.status = DeliveryStatus.READY;
        return delivery;
    }
}
