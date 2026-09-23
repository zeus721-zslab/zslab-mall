package com.zslab.mall.reconciliation.entity;

import com.zslab.mall.reconciliation.enums.ReconciliationIssueStatus;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueType;
import com.zslab.mall.reconciliation.exception.ReconciliationIssueInvalidStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 주문·결제 불일치 1건(Track 104-2 D-216·invariants P1·P6·V35). 생성은 {@code ReconciliationIssueRecorder}의 멱등 INSERT(같은 유형·중복 키는
 * 1행)로만 하고, 엔티티는 조회·해결 전이에 쓴다. 대상 id는 전부 논리 참조다(FK 없음 — 미결제 주문 hard delete를 막지 않고 매칭 행 없는 PG 통지도 담는다).
 */
@Entity
@Table(name = "reconciliation_issue")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ReconciliationIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, updatable = false)
    private ReconciliationIssueType issueType;

    @Column(name = "dedupe_key", nullable = false, updatable = false, length = 191)
    private String dedupeKey;

    @Column(name = "order_id", updatable = false)
    private Long orderId;

    @Column(name = "payment_id", updatable = false)
    private Long paymentId;

    @Column(name = "refund_id", updatable = false)
    private Long refundId;

    @Column(name = "claim_id", updatable = false)
    private Long claimId;

    @Column(name = "delivery_id", updatable = false)
    private Long deliveryId;

    @Column(name = "pg_tid", updatable = false, length = 100)
    private String pgTid;

    @Column(name = "pg_refund_id", updatable = false, length = 100)
    private String pgRefundId;

    @Column(name = "detail", columnDefinition = "LONGTEXT", updatable = false)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReconciliationIssueStatus status;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private LocalDateTime detectedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolution_memo", length = 500)
    private String resolutionMemo;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 불일치를 해결 처리한다(OPEN → RESOLVED 한 번뿐). 데이터 보정은 하지 않는다 — 운영자가 확인·조치했다는 기록이다.
     *
     * @throws ReconciliationIssueInvalidStateException 이미 해결된 경우(422)
     * @throws IllegalArgumentException                 해결자·메모·시각 누락
     */
    public void resolve(Long adminUserId, String memo, LocalDateTime resolvedAt) {
        if (adminUserId == null || memo == null || memo.isBlank() || resolvedAt == null) {
            throw new IllegalArgumentException("불일치 해결에는 처리자·메모·시각이 필요합니다.");
        }
        if (status == ReconciliationIssueStatus.RESOLVED) {
            throw new ReconciliationIssueInvalidStateException("이미 해결된 불일치입니다: id=" + id);
        }
        this.status = ReconciliationIssueStatus.RESOLVED;
        this.resolvedBy = adminUserId;
        this.resolutionMemo = memo;
        this.resolvedAt = resolvedAt;
    }
}
