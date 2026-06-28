package com.zslab.mall.seller.entity;

import com.zslab.mall.common.entity.AbstractFullAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 종료 판매자 아카이브(Seller 종속·ARCHIVE·D-23·SLR-6).
 *
 * <p>originalSeller는 Seller Aggregate 내부 참조 — D-01에 따라 @ManyToOne LAZY 허용(D-85 Q2·D-23 SLR-7).
 * WithdrawnUser 패턴 준용. deleted_at 없음(ARCHIVE 분류) — soft-delete 미적용.
 * SLR-7: Seller·WithdrawnSeller 동시 수정 금지·Seller SoT.
 */
@Entity
@Table(name = "withdrawn_seller")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawnSeller extends AbstractFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_seller_id", nullable = false, updatable = false)
    private Seller originalSeller;

    @Column(name = "terminate_reason", length = 255)
    private String terminateReason;

    @Column(name = "legal_retention_until")
    private LocalDateTime legalRetentionUntil;

    @Column(name = "anonymized_at")
    private LocalDateTime anonymizedAt;

    /**
     * @throws IllegalArgumentException originalSeller 누락 시
     */
    public static WithdrawnSeller create(
            Seller originalSeller,
            String terminateReason,
            LocalDateTime legalRetentionUntil) {
        if (originalSeller == null) {
            throw new IllegalArgumentException("WithdrawnSeller 필수값 누락(originalSeller).");
        }
        WithdrawnSeller withdrawnSeller = new WithdrawnSeller();
        withdrawnSeller.originalSeller = originalSeller;
        withdrawnSeller.terminateReason = terminateReason;
        withdrawnSeller.legalRetentionUntil = legalRetentionUntil;
        return withdrawnSeller;
    }
}
