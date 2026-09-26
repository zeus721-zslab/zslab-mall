package com.zslab.mall.delivery.entity;

import com.zslab.mall.common.entity.AbstractPublicIdFullAuditableEntity;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryDirection;
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
 * trackingNo는 nullable·유니크 아님(DLV-1·D-227 — 합포장·택배사 번호 재사용).
 * deleted_at 없음(ARCHIVE 분류) — soft-delete 미적용.
 *
 * <p>equals/hashCode·toString은 {@link AbstractPublicIdFullAuditableEntity}가 publicId 기준으로 제공한다.
 */
@Entity
@Table(name = "delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Delivery extends AbstractPublicIdFullAuditableEntity {

    /**
     * 송장번호 입력 형식(D-227). 송장 입력 요청 DTO 전부가 {@link #stripTrackingNo}로 앞뒤 공백을 제거한 뒤 이 규칙으로 검증한다.
     * 규칙 이전에 저장된 값은 보정하지 않으므로 엔티티에서는 강제하지 않는다.
     */
    public static final String TRACKING_NO_PATTERN = "^[A-Za-z0-9-]{8,20}$";
    public static final String TRACKING_NO_FORMAT_MESSAGE = "송장번호는 영문, 숫자, 하이픈(-)만 사용해 8~20자로 입력해 주세요.";

    /** 요청 DTO 생성 시 검증 전에 호출한다 — {@code @Pattern}이 공백 제거 후 값을 보도록(D-227). */
    public static String stripTrackingNo(String trackingNo) {
        return trackingNo == null ? null : trackingNo.strip();
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_item_id", nullable = false, updatable = false)
    private Long orderItemId;

    /** 배송 방향(Track 81-A D-170). OUTBOUND=발송(기본)·RETURN=반품 회수. */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false)
    private DeliveryDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "carrier", nullable = false)
    private DeliveryCarrier carrier;

    /** 발송 전 NULL 허용. 합포장·택배사 번호 재사용으로 여러 행이 같은 번호를 쓸 수 있다(DLV-1·D-227 유니크 제거). */
    @Column(name = "tracking_no", length = 100)
    private String trackingNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DeliveryStatus status;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    /**
     * 클레임 Delivery 참조 (D-98 Q13·D-170 확장). 일반 주문 Delivery는 NULL, 교환품 발송·반품 회수(RETURN)·검수 불합격 재발송은 NOT NULL.
     * Aggregate 외부 ID 참조(D-01) — JPA 연관(@ManyToOne) 미사용.
     */
    @Column(name = "claim_id")
    private Long claimId;

    @Override
    protected String getPublicIdPrefix() {
        return "dlv";
    }

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static Delivery create(Long orderItemId, DeliveryCarrier carrier) {
        return create(orderItemId, carrier, DeliveryDirection.OUTBOUND);
    }

    /**
     * 방향을 지정해 생성한다(Track 81-A). 반품 회수는 {@link DeliveryDirection#RETURN}.
     *
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static Delivery create(Long orderItemId, DeliveryCarrier carrier, DeliveryDirection direction) {
        if (orderItemId == null || carrier == null || direction == null) {
            throw new IllegalArgumentException("Delivery 필수값 누락(orderItemId·carrier·direction).");
        }
        Delivery delivery = new Delivery();
        delivery.orderItemId = orderItemId;
        delivery.carrier = carrier;
        delivery.direction = direction;
        delivery.status = DeliveryStatus.READY;
        return delivery;
    }

    /**
     * 교환품 Claim 연결(D-98 Q13·{@code DeliveryService.registerExchangeShipment} 진입부 호출). 본 메서드는 단순
     * setter이며 type·orderItemId 불변식 검증은 호출처({@code Claim.attachExchangeDelivery})가 수행한다.
     *
     * @throws IllegalArgumentException claimId가 null인 경우
     */
    public void attachExchangeClaim(Long claimId) {
        attachClaim(claimId);
    }

    /**
     * 클레임 연결(Track 81-A D-170·반품 회수·검수 불합격 재발송). 단순 setter이며 클레임 유형·품목 불변식 검증은 호출처 책임이다.
     *
     * @throws IllegalArgumentException claimId가 null인 경우
     */
    public void attachClaim(Long claimId) {
        if (claimId == null) {
            throw new IllegalArgumentException("attachClaim: claimId는 필수입니다.");
        }
        this.claimId = claimId;
    }

    /**
     * 발송 처리(READY → SHIPPING·D-97 Q2). {@link DeliveryStatus#canTransitionTo}로 전이 합법성을 검증한 뒤
     * 운송장번호·발송 시각·상태를 설정한다. 이벤트 발행은 {@code DeliveryService} 책임이다(D-29 save→publish).
     *
     * @throws IllegalStateException 불법 배송 상태 전이 또는 trackingNo·shippedAt 누락 시
     */
    public void markShipping(String trackingNo, LocalDateTime shippedAt) {
        if (!status.canTransitionTo(DeliveryStatus.SHIPPING)) {
            throw new IllegalStateException("불법 배송 상태 전이: " + status + " → " + DeliveryStatus.SHIPPING);
        }
        // D-98 Q3·외부 검토 1차 Q1 α 흡수·DLV-3 정합·Service 재검증 금지
        if (trackingNo == null || trackingNo.isBlank()) {
            throw new IllegalStateException("markShipping: trackingNo는 필수입니다.");
        }
        if (shippedAt == null) {
            throw new IllegalStateException("markShipping: shippedAt는 필수입니다.");
        }
        this.trackingNo = trackingNo;
        this.shippedAt = shippedAt;
        this.status = DeliveryStatus.SHIPPING;
    }

    /**
     * 송장 정정(Track 89-B D-184). 잘못 입력된 택배사·송장번호를 바로잡는다 — 상태·shippedAt은 바꾸지 않는다(전이 아님).
     * SHIPPING에서만 허용한다: READY는 송장이 아직 없고({@link #markShipping}이 최초 설정), DELIVERED는 종결 상태로 반품 기한·
     * 자동 구매확정이 이미 그 행을 기준으로 계산됐기 때문이다.
     *
     * @throws IllegalStateException SHIPPING이 아니거나 carrier·trackingNo 누락 시
     */
    public void correctTracking(DeliveryCarrier carrier, String trackingNo) {
        if (status != DeliveryStatus.SHIPPING) {
            throw new IllegalStateException("송장 정정은 배송중(SHIPPING)에서만 가능합니다: status=" + status);
        }
        if (carrier == null || trackingNo == null || trackingNo.isBlank()) {
            throw new IllegalStateException("correctTracking: carrier·trackingNo는 필수입니다.");
        }
        this.carrier = carrier;
        this.trackingNo = trackingNo;
    }

    /**
     * 배송 완료 처리(SHIPPING → DELIVERED·D-97 Q2·WARN-7). 전이 합법성 검증 후 DLV-3(shipped_at ≤ delivered_at·
     * invariants §2.12)를 강제한다. 이벤트 발행은 {@code DeliveryService} 책임이다(D-29 save→publish).
     *
     * @throws IllegalStateException 불법 배송 상태 전이 또는 DLV-3 위반 시
     */
    public void markDelivered(LocalDateTime deliveredAt) {
        if (!status.canTransitionTo(DeliveryStatus.DELIVERED)) {
            throw new IllegalStateException("불법 배송 상태 전이: " + status + " → " + DeliveryStatus.DELIVERED);
        }
        if (shippedAt != null && deliveredAt.isBefore(shippedAt)) {
            throw new IllegalStateException("DLV-3 위반·shipped_at ≤ delivered_at 정합 깨짐");
        }
        this.deliveredAt = deliveredAt;
        this.status = DeliveryStatus.DELIVERED;
    }
}
