package com.zslab.mall.claim.entity;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.entity.AbstractPublicIdFullAuditableEntity;
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

    @Override
    protected String getPublicIdPrefix() {
        return "clm";
    }

    /**
     * 클레임을 생성한다. 초기 상태는 {@link ClaimStatus#REQUESTED}이며 public_id(clm_)는 @PrePersist에서 발급된다.
     *
     * <p>본 트랙은 요청 API를 구현하지 않으므로(expected-spec §1.2) 본 팩토리는 테스트 시드·후속 트랙 진입점 용도다.
     *
     * @throws IllegalArgumentException 필수값(orderItemId·type·reasonCode) 누락 시
     */
    public static Claim create(
            Long orderItemId,
            ClaimType type,
            String reasonCode,
            String reasonDetail,
            Long requestedBy,
            LocalDateTime requestedAt) {
        if (orderItemId == null || type == null || reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("Claim 필수값 누락(orderItemId·type·reasonCode).");
        }
        Claim claim = new Claim();
        claim.orderItemId = orderItemId;
        claim.type = type;
        claim.reasonCode = reasonCode;
        claim.reasonDetail = reasonDetail;
        claim.requestedBy = requestedBy;
        claim.requestedAt = requestedAt;
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
     * @throws IllegalStateException APPROVED가 아니어서 COMPLETED 전이가 불가한 경우(CLM-1·CLM-4)
     * @throws IllegalArgumentException processedAt가 null인 경우
     */
    public void markCompleted(LocalDateTime processedAt) {
        if (processedAt == null) {
            throw new IllegalArgumentException("markCompleted: processedAt는 필수입니다.");
        }
        transitionTo(ClaimStatus.COMPLETED);
        this.processedAt = processedAt;
    }

    /** 상태를 {@code next}로 전이한다. {@link ClaimStatus#canTransitionTo}로 합법성을 검증한다(CLM-4). */
    private void transitionTo(ClaimStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("불법 클레임 상태 전이: " + status + " → " + next);
        }
        this.status = next;
    }
}
