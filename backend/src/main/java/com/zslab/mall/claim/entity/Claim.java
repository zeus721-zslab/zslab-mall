package com.zslab.mall.claim.entity;

import com.zslab.mall.claim.enums.ClaimInspectionResult;
import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
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
import jakarta.persistence.Version;
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

    /** 거부 메모 최대 길이(claim.reject_memo VARCHAR(500)·V23). */
    public static final int REJECT_MEMO_MAX_LENGTH = 500;

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

    /** 교환 클레임 환불 금액(D-115 결정2). 승인 시 확정하며 NULL·0은 차액 없는 교환(=Refund 미경유). */
    @Column(name = "refund_amount")
    private Long refundAmount;

    /** 거부 사유 코드(Track 80 D-169). 거부 시점에만 채워지며 그 외 NULL. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reject_reason_code", length = 50)
    private ClaimRejectReasonCode rejectReasonCode;

    /** 거부 메모(선택·500자). */
    @Column(name = "reject_memo", length = 500)
    private String rejectMemo;

    @Column(name = "picked_up_at")
    private LocalDateTime pickedUpAt;

    /** 반품 검수 시각(Track 81-A D-170). 회수 확인 후 검수 시점에만 채워진다. */
    @Column(name = "inspected_at")
    private LocalDateTime inspectedAt;

    /** 반품 검수 결과(PASS|FAIL). 미검수 NULL. */
    @Enumerated(EnumType.STRING)
    @Column(name = "inspection_result", length = 20)
    private ClaimInspectionResult inspectionResult;

    /** 검수 PASS 시 재입고 여부(불량품 폐기 = false). FAIL·미검수 NULL. */
    @Column(name = "restock")
    private Boolean restock;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_order_item_status", length = 20, nullable = false, updatable = false)
    private OrderItemStatus previousOrderItemStatus;

    /** JPA 낙관적 락(D-115 결정4). 교환 종결 전이(tryCompleteExchange) 동시 진입 방어. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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
     * 반품 검수 합격을 적용한다(Track 81-A D-170·R5). 상태 전이 없이 검수 milestone(inspectedAt·PASS·restock)만 채운다.
     * 환불 개시는 Service가 {@code ClaimInspectionPassed} 발행으로 트리거한다(수거 확인 시점 환불 → 검수 PASS 시점으로 이동).
     *
     * @param restock     재입고 여부(필수)
     * @param inspectedAt 검수 시각
     * @throws ClaimInvalidStateException type != RETURN·APPROVED 아님·미회수(pickedUpAt null)·이미 검수됨
     * @throws IllegalArgumentException   restock·inspectedAt이 null인 경우
     */
    public void passInspection(Boolean restock, LocalDateTime inspectedAt) {
        if (restock == null || inspectedAt == null) {
            throw new IllegalArgumentException("passInspection: restock·inspectedAt는 필수입니다.");
        }
        requireInspectable();
        this.inspectedAt = inspectedAt;
        this.inspectionResult = ClaimInspectionResult.PASS;
        this.restock = restock;
    }

    /**
     * 반품 검수 불합격을 적용한다(Track 81-A D-170·R5). 검수 milestone(FAIL)을 채우고 거부 사유와 함께 <b>APPROVED → REJECTED</b>로
     * 전이한다. 이 전이는 {@link ClaimStatus#canTransitionTo} 매트릭스 밖의 예외이며 RETURN 검수 경로에서만 허용한다(일반 거부는
     * {@link #reject}·REQUESTED 한정). 품목 원복·재발송은 Service·핸들러 책임이다.
     *
     * @param reasonCode  거부 사유 코드(필수·RETURN 적합 사유)
     * @param memo        거부 메모(선택·500자)
     * @param inspectedAt 검수 시각(processedAt으로도 기록)
     * @throws ClaimInvalidStateException type != RETURN·APPROVED 아님·미회수·이미 검수됨
     * @throws IllegalArgumentException   필수값 누락·사유 부적합·메모 길이 초과
     */
    public void failInspection(ClaimRejectReasonCode reasonCode, String memo, LocalDateTime inspectedAt) {
        if (reasonCode == null || inspectedAt == null) {
            throw new IllegalArgumentException("failInspection: reasonCode·inspectedAt는 필수입니다.");
        }
        if (!reasonCode.isApplicableTo(this.type)) {
            throw new IllegalArgumentException(
                    "failInspection: 거부 사유 " + reasonCode + "은(는) " + this.type + " 클레임에 사용할 수 없습니다.");
        }
        if (memo != null && memo.length() > REJECT_MEMO_MAX_LENGTH) {
            throw new IllegalArgumentException("failInspection: 거부 메모는 " + REJECT_MEMO_MAX_LENGTH + "자 이하여야 합니다.");
        }
        requireInspectable();
        this.inspectedAt = inspectedAt;
        this.inspectionResult = ClaimInspectionResult.FAIL;
        this.restock = null;
        // RETURN 검수 불합격 한정 예외 전이(D-170): canTransitionTo를 우회하므로 transitionTo를 쓰지 않는다.
        this.status = ClaimStatus.REJECTED;
        this.processedAt = inspectedAt;
        this.rejectReasonCode = reasonCode;
        this.rejectMemo = memo;
    }

    /** 검수 가능 조건: RETURN·APPROVED·회수 확인됨·미검수. */
    private void requireInspectable() {
        if (this.type != ClaimType.RETURN) {
            throw new ClaimInvalidStateException("검수는 RETURN 클레임에서만 가능합니다: type=" + this.type);
        }
        if (this.status != ClaimStatus.APPROVED) {
            throw new ClaimInvalidStateException("검수는 APPROVED 클레임에서만 가능합니다: " + this.status);
        }
        if (this.pickedUpAt == null) {
            throw new ClaimInvalidStateException("회수 확인 전에는 검수할 수 없습니다.");
        }
        if (this.inspectionResult != null) {
            throw new ClaimInvalidStateException("이미 검수된 클레임입니다: " + this.inspectionResult);
        }
    }

    /** 검수 PASS 후 재입고 대상인지(RETURN 종결 시 재고 복구 분기·Track 81-A). */
    public boolean isRestockRequested() {
        return Boolean.TRUE.equals(restock);
    }

    /**
     * 클레임을 승인한다(REQUESTED → APPROVED·CLM-4). {@code processedAt}을 처리 시각으로 채운다.
     *
     * <p>Buyer 요청 후 Seller/Admin 승인 흐름(Track 10 endpoint)에서 {@code ClaimService.approve}가 호출한다.
     *
     * <p>EXCHANGE 차액환불(D-115 결정2): {@code refundAmount}는 승인 시점에 확정한다. NULL은 차액 없는 교환(=Refund
     * 미경유·기존 동작)이며, CANCEL·RETURN 등 비교환 클레임은 NULL을 전달한다(환불 금액은 별도 산정 경로 소관).
     *
     * @param processedAt  승인 처리 시각(시스템 시각)
     * @param refundAmount 교환 차액 환불 금액(EXCHANGE 한정·NULL=차액 없음). 비교환은 NULL.
     * @throws ClaimInvalidStateException REQUESTED가 아니어서 APPROVED 전이가 불가한 경우(CLM-4)
     * @throws IllegalArgumentException processedAt가 null이거나 refundAmount가 음수인 경우
     */
    public void approve(LocalDateTime processedAt, Long refundAmount) {
        if (processedAt == null) {
            throw new IllegalArgumentException("approve: processedAt는 필수입니다.");
        }
        if (refundAmount != null && refundAmount < 0) {
            throw new IllegalArgumentException("approve: refundAmount는 음수일 수 없습니다. 입력: " + refundAmount);
        }
        transitionTo(ClaimStatus.APPROVED);
        this.processedAt = processedAt;
        this.refundAmount = refundAmount;
    }

    /**
     * 교환 차액 환불이 발생하는 클레임인지 판정한다(D-115 결정2·결정3). {@code refundAmount > 0}일 때만 true다.
     * NULL·0은 차액 없는 교환으로 Refund를 경유하지 않는다(기존 동작 100% 보존).
     */
    public boolean hasRefundDifference() {
        return refundAmount != null && refundAmount > 0;
    }

    /**
     * 클레임을 거절한다(REQUESTED → REJECTED·CLM-4). 거절 이력은 보존되며 재요청은 새 Claim 행이다(CLM-2).
     *
     * <p>거부 사유 코드는 필수·메모는 선택이다(Track 80 D-169). {@link ClaimRejectReasonCode#ALREADY_SHIPPED}는 CANCEL 전용이며
     * 다른 유형에 전달되면 거부하지 않고 400으로 되돌린다.
     *
     * @param reasonCode  거부 사유 코드(필수)
     * @param memo        거부 메모(선택·500자 이하)
     * @param processedAt 거절 처리 시각(시스템 시각)
     * @throws ClaimInvalidStateException REQUESTED가 아니어서 REJECTED 전이가 불가한 경우(CLM-4)
     * @throws IllegalArgumentException processedAt·reasonCode가 null이거나 사유가 유형에 부적합·메모 500자 초과인 경우
     */
    public void reject(ClaimRejectReasonCode reasonCode, String memo, LocalDateTime processedAt) {
        if (processedAt == null) {
            throw new IllegalArgumentException("reject: processedAt는 필수입니다.");
        }
        if (reasonCode == null) {
            throw new IllegalArgumentException("reject: 거부 사유 코드는 필수입니다.");
        }
        if (!reasonCode.isApplicableTo(this.type)) {
            throw new IllegalArgumentException(
                    "reject: 거부 사유 " + reasonCode + "은(는) " + this.type + " 클레임에 사용할 수 없습니다.");
        }
        if (memo != null && memo.length() > REJECT_MEMO_MAX_LENGTH) {
            throw new IllegalArgumentException("reject: 거부 메모는 " + REJECT_MEMO_MAX_LENGTH + "자 이하여야 합니다.");
        }
        transitionTo(ClaimStatus.REJECTED);
        this.processedAt = processedAt;
        this.rejectReasonCode = reasonCode;
        this.rejectMemo = memo;
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
