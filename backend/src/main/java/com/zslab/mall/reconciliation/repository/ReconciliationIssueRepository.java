package com.zslab.mall.reconciliation.repository;

import com.zslab.mall.reconciliation.entity.ReconciliationIssue;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueStatus;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueType;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 불일치 기록 Repository(Track 104-2 D-216). {@link JpaSpecificationExecutor}는 관리자 목록(상태·유형 필터) 전용이다.
 */
public interface ReconciliationIssueRepository
        extends JpaRepository<ReconciliationIssue, Long>, JpaSpecificationExecutor<ReconciliationIssue> {

    /**
     * 불일치 1건을 멱등 INSERT한다 — (issue_type, dedupe_key)가 이미 있으면 기존 행을 그대로 두고 0을 돌려준다. 예외 대신
     * {@code ON DUPLICATE KEY UPDATE id = id}(무변경)를 쓰는 이유: 거부하지 않는 PG 통지 처리 트랜잭션 안에서 UNIQUE 위반 예외가 나면
     * 트랜잭션이 롤백 전용이 되어 통지 처리 자체가 실패한다. 모든 변수는 :name 바인딩 사용, SQL injection 위험 없음.
     *
     * @return 새로 기록했으면 1, 이미 있던 불일치면 0
     */
    @Modifying
    @Query(value = "INSERT INTO reconciliation_issue (issue_type, dedupe_key, order_id, payment_id, refund_id, claim_id, "
            + "delivery_id, pg_tid, pg_refund_id, detail, status, detected_at, created_at) "
            + "VALUES (:issueType, :dedupeKey, :orderId, :paymentId, :refundId, :claimId, :deliveryId, :pgTid, :pgRefundId, "
            + ":detail, 'OPEN', :detectedAt, :detectedAt) "
            + "ON DUPLICATE KEY UPDATE id = id", nativeQuery = true)
    int insertIfAbsent(
            @Param("issueType") String issueType,
            @Param("dedupeKey") String dedupeKey,
            @Param("orderId") Long orderId,
            @Param("paymentId") Long paymentId,
            @Param("refundId") Long refundId,
            @Param("claimId") Long claimId,
            @Param("deliveryId") Long deliveryId,
            @Param("pgTid") String pgTid,
            @Param("pgRefundId") String pgRefundId,
            @Param("detail") String detail,
            @Param("detectedAt") LocalDateTime detectedAt);

    /** 대시보드 처리 대기(열린 불일치 수). */
    long countByStatus(ReconciliationIssueStatus status);

    /** 해결 처리용 행 락 조회 — 두 운영자가 동시에 해결하면 뒤 요청이 해결된 상태를 보고 422가 된다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT issue FROM ReconciliationIssue issue WHERE issue.id = :id")
    Optional<ReconciliationIssue> findByIdForUpdate(@Param("id") Long id);

    /**
     * 열린 행만 시스템 해결 처리한다(조건부 UPDATE·처리자 NULL). 관리자 해결과 겹치면 행 락에서 기다린 뒤 status 조건이 다시 평가돼 0행이 된다 —
     * 엔티티를 읽고 고쳐 쓰면 그사이 커밋된 관리자 해결(처리자·메모)을 덮어쓴다(외부 검토 지적 3·RED 실측).
     *
     * @return 이번에 해결한 행 수(0 또는 1)
     */
    @Modifying
    @Query("UPDATE ReconciliationIssue issue SET issue.status = :resolved, issue.resolvedAt = :resolvedAt, issue.resolutionMemo = :memo "
            + "WHERE issue.issueType = :issueType AND issue.dedupeKey = :dedupeKey AND issue.status = :open")
    int resolveBySystemIfOpen(
            @Param("issueType") ReconciliationIssueType issueType,
            @Param("dedupeKey") String dedupeKey,
            @Param("memo") String memo,
            @Param("resolvedAt") LocalDateTime resolvedAt,
            @Param("resolved") ReconciliationIssueStatus resolved,
            @Param("open") ReconciliationIssueStatus open);

    /** 유형·중복 키로 1행 조회(UNIQUE 인덱스) — 시스템 해결 뒤 감사 대상 id 찾기. */
    Optional<ReconciliationIssue> findByIssueTypeAndDedupeKey(ReconciliationIssueType issueType, String dedupeKey);

    /**
     * 주문에 해당 상태의 불일치가 있는지(Track 104-4 구매확정 가드). 유형 제한 없음 — 정산 보류(D-218 SALE·REFUND 원천 쿼리의 OPEN NOT
     * EXISTS)와 같은 의미다. 파생 쿼리 바인딩.
     */
    boolean existsByOrderIdAndStatus(Long orderId, ReconciliationIssueStatus status);

    /** 주문 상세의 불일치 섹션(최신순). */
    List<ReconciliationIssue> findByOrderIdOrderByIdDesc(Long orderId);
}
