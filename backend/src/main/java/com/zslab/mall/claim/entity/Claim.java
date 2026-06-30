package com.zslab.mall.claim.entity;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.common.entity.AbstractPublicIdFullAuditableEntity;
import com.zslab.mall.order.enums.OrderItemStatus;
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
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 클레임(CLM Aggregate Root·ARCHIVE·public_id {@code clm_}). Refund의 상위 Aggregate Root다(aggregate-boundary §2.5).
 *
 * <p><b>최소 골격(Track 5)</b>: 본 트랙은 엔티티·enum·canTransitionTo·Repository·markCompleted 전이 1건만 박제한다.
 * 요청·승인/거절 워크플로우는 후속 트랙 소관이며(expected-spec §1.2), APPROVED 상태 진입은 테스트 시드로 직접 구성한다.
 *
 * <p>OrderItem은 {@code orderItemId}(OrderItem.id)로 외부 참조하며 JPA 연관(@ManyToOne)을 두지 않는다(aggregate-boundary §1·ID 참조).
 *
 * <p>equals/hashCode·toString은 {@link AbstractPublicIdFullAuditableEntity}가 publicId 기준으로 제공한다.
 */
@Entity
@Table(name = "claim")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Claim extends AbstractPublicIdFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private ClaimType type;

    @Column(name = "reason_code", length = 50, nullable = false)
    private String reasonCode;

    @Column(name = "reason_detail")
    private String reasonDetail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "picked_up_at")
    private LocalDateTime pickedUpAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_order_item_status", length = 20, nullable = false, updatable = false)
    private OrderItemStatus previousOrderItemStatus;

    @Override
    protected String getPublicIdPrefix() {
        return "clm";
    }

    /**
     * 클레임을 생성한다. 초기 상태는 {@link ClaimStatus#REQUESTED}이며 public_id(clm_)는 @PrePersist에서 발급된다.
     *
     * <p>본 트랙은 요청 API를 구현하지 않으므로(expected-spec §1.2) 본 팩토리는 테스트 시드·후속 트랙 진입점 용도다.
     *
     * @throws IllegalArgumentException 필수값(orderItemId·type·reasonCode·previousOrderItemStatus) 누락 시
     */
    public static Claim create(
            Long orderItemId,
            ClaimType type,
            String reasonCode,
            String reasonDetail,
            Long requestedBy,
            LocalDateTime requestedAt,
            OrderItemStatus previousOrderItemStatus) {
        if (orderItemId == null || type == null || reasonCode == null || reasonCode.isBlank()
                || previousOrderItemStatus == null) {
            throw new IllegalArgumentException(
                    "Claim 필수값 누락(orderItemId·type·reasonCode·previousOrderItemStatus).");
        }
        Claim claim = new Claim();
        claim.orderItemId = orderItemId;
        claim.type = type;
        claim.reasonCode = reasonCode;
        claim.reasonDetail = reasonDetail;
        claim.requestedBy = requestedBy;
        claim.requestedAt = requestedAt;
        claim.previousOrderItemStatus = previousOrderItemStatus;
        claim.status = ClaimStatus.REQUESTED;
        return claim;
    }

    /**
     * 클레임 종결을 적용한다(APPROVED → COMPLETED·CLM-4). {@code processedAt}을 처리 시각으로 채운다.
     *
     * <p>Refund.COMPLETED 콜백 후 {@code ClaimService}가 호출한다. 멱등(이미 COMPLETED 시 no-op) 판단은 Service 책임이며,
     * 본 메서드는 합법 전이만 수행한다.
     *
     * @param processedAt 종결 처리 시각(시스템 시각)
     * @throws ClaimInvalidStateException APPROVED가 아니어서 COMPLETED 전이가 불가한 경우(CLM-1·CLM-4)
     * @throws IllegalArgumentException processedAt가 null인 경우
     */
    public void markCompleted(LocalDateTime processedAt) {
        if (processedAt == null) {
            throw new IllegalArgumentException("markCompleted: processedAt는 필수입니다.");
        }
        transitionTo(ClaimStatus.COMPLETED);
        this.processedAt = processedAt;
    }

    /**
     * 클레임 수거 확인을 적용한다(D-98 Q1·E11 ClaimPickedUp 발행 트리거·RETURN/EXCHANGE 흐름). 상태 전이 없이
     * {@code pickedUpAt}만 채운다(수거는 milestone 사실이며 ClaimStatus는 APPROVED 유지).
     *
     * <p>가드: status가 APPROVED여야 한다(CLM-4 정합). 멱등(이미 picked_up_at != null 시 no-op) 판단은 Service
     * 책임이며({@code ClaimService.confirmPickup}·markCompleted 패턴 1:1), 본 메서드는 합법 상태에서의 설정만 수행한다.
     *
     * @param pickedUpAt 수거 확인 시각
     * @throws ClaimInvalidStateException APPROVED가 아닌 경우(CLM-4)
     * @throws IllegalArgumentException   pickedUpAt가 null인 경우
     */
    public void confirmPickup(LocalDateTime pickedUpAt) {
        if (pickedUpAt == null) {
            throw new IllegalArgumentException("confirmPickup: pickedUpAt는 필수입니다.");
        }
        if (this.status != ClaimStatus.APPROVED) {
            throw new ClaimInvalidStateException("수거 확인은 APPROVED 클레임에서만 가능합니다: " + this.status);
        }
        this.pickedUpAt = pickedUpAt;
    }

    /**
     * 클레임을 승인한다(REQUESTED → APPROVED·CLM-4). {@code processedAt}을 처리 시각으로 채운다.
     *
     * <p>Buyer 요청 후 Seller/Admin 승인 흐름(Track 10 endpoint)에서 {@code ClaimService.approve}가 호출한다.
     *
     * @param processedAt 승인 처리 시각(시스템 시각)
     * @throws ClaimInvalidStateException REQUESTED가 아니어서 APPROVED 전이가 불가한 경우(CLM-4)
     * @throws IllegalArgumentException processedAt가 null인 경우
     */
    public void approve(LocalDateTime processedAt) {
        if (processedAt == null) {
            throw new IllegalArgumentException("approve: processedAt는 필수입니다.");
        }
        transitionTo(ClaimStatus.APPROVED);
        this.processedAt = processedAt;
    }

    /**
     * 클레임을 거절한다(REQUESTED → REJECTED·CLM-4). 거절 이력은 보존되며 재요청은 새 Claim 행이다(CLM-2).
     *
     * @param processedAt 거절 처리 시각(시스템 시각)
     * @throws ClaimInvalidStateException REQUESTED가 아니어서 REJECTED 전이가 불가한 경우(CLM-4)
     * @throws IllegalArgumentException processedAt가 null인 경우
     */
    public void reject(LocalDateTime processedAt) {
        if (processedAt == null) {
            throw new IllegalArgumentException("reject: processedAt는 필수입니다.");
        }
        transitionTo(ClaimStatus.REJECTED);
        this.processedAt = processedAt;
    }

    /**
     * 교환 배송 Delivery를 본 Claim에 연결한다(D-98 Q13·외부 검토 1차 Q3 신규 의제 P1 흡수).
     *
     * <p>호출처: {@code DeliveryService.registerExchangeShipment} 진입부·{@code Delivery.create} 직후.
     * RETURN/CANCEL/일반 주문은 {@code claimId == null}·본 메서드 미경유.
     *
     * <p>Aggregate 불변식:
     * <ul>
     *   <li>{@code this.type == ClaimType.EXCHANGE} — API 실수로 RETURN/CANCEL 연결 차단
     *   <li>{@code this.orderItemId == deliveryOrderItemId} — Delivery-OrderItem 일관성
     * </ul>
     *
     * <p>본 메서드 자체는 검증만 수행한다(반환 void·필드 변경 없음). Delivery.claim_id 설정은
     * {@code Delivery.attachExchangeClaim}이 담당하며 호출 책임은 DeliveryService에 있다(D-01 Aggregate 외부 ID).
     *
     * @param deliveryId            교환품 Delivery.id
     * @param deliveryOrderItemId   Delivery.orderItemId (Delivery.create 시점 인자)
     * @throws ClaimInvalidStateException type != EXCHANGE 또는 orderItemId 불일치
     * @throws IllegalArgumentException   필수값 누락
     */
    public void attachExchangeDelivery(Long deliveryId, Long deliveryOrderItemId) {
        if (deliveryId == null || deliveryOrderItemId == null) {
            throw new IllegalArgumentException("attachExchangeDelivery: deliveryId·deliveryOrderItemId는 필수입니다.");
        }
        if (this.type != ClaimType.EXCHANGE) {
            throw new ClaimInvalidStateException(
                    "교환 배송 연결은 EXCHANGE 클레임에서만 가능합니다: type=" + this.type);
        }
        if (!this.orderItemId.equals(deliveryOrderItemId)) {
            throw new ClaimInvalidStateException(
                    "Delivery-OrderItem 불일치: claim.orderItemId=" + this.orderItemId
                            + ", delivery.orderItemId=" + deliveryOrderItemId);
        }
    }

    /**
     * 상태를 {@code next}로 전이한다. {@link ClaimStatus#canTransitionTo}로 합법성을 검증한다(CLM-4).
     *
     * @throws ClaimInvalidStateException 비합법 전이인 경우(CLM-3 책임·500 fallback 차단·422 매핑)
     */
    private void transitionTo(ClaimStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new ClaimInvalidStateException("불법 클레임 상태 전이: " + status + " → " + next);
        }
        this.status = next;
    }
}
