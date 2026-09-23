package com.zslab.mall.reconciliation.repository;

import com.zslab.mall.reconciliation.entity.ReconciliationIssue;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 점검 스케줄러 후보 조회(Track 104-2 D-216). 저장된 행만으로 판정 가능한 불일치 패턴을 찾는다 — 조회 전용이며 업무 데이터를 바꾸지 않는다.
 *
 * <p><b>공통 인자</b>: {@code issueType}·{@code dedupePrefix}는 이미 기록된 대상(해결 포함)을 빼는 데 쓰고, {@code targetId}가 null이면 전체·
 * 값이 있으면 그 대상 1건만 다시 판정한다(건별 기록 직전 재확인). {@code afterId}는 대상 id 커서(이 값보다 큰 id만·재확인은 0)이고
 * {@code limit}은 한 페이지 크기다 — 기록되지 않고 남는 후보(재확인 해소·기록 실패)가 앞 페이지를 차지해도 뒤 후보가 굶지 않게 한다(외부 검토
 * 지적 5). 상태 리터럴은 DDL ENUM 값 그대로이며 모든 변수는 :name 바인딩 사용, SQL injection 위험 없음.
 */
public interface ReconciliationCandidateRepository extends Repository<ReconciliationIssue, Long> {

    /** 결제 후보 공통 SELECT(완료 환불 합계 파생 테이블 rs 조인 전제). */
    String PAYMENT_SELECT = "SELECT p.id AS targetId, p.order_id AS orderId, p.id AS paymentId, NULL AS claimId, "
            + "NULL AS deliveryId, NULL AS orderItemId, p.pg_tid AS pgTid, p.status AS paymentStatus, p.amount AS paymentAmount, "
            + "CAST(COALESCE(rs.refunded, 0) AS SIGNED) AS refundedAmount, NULL AS itemStatus, NULL AS claimType, "
            + "NULL AS claimStatus, NULL AS deliveryStatus "
            + "FROM payment p "
            + "LEFT JOIN (SELECT payment_id, SUM(amount) AS refunded FROM refund WHERE status = 'COMPLETED' "
            + "GROUP BY payment_id) rs ON rs.payment_id = p.id ";

    /** 결제 후보 공통 조건 — 대상 1건 재확인·기록된 대상 제외. */
    String PAYMENT_TAIL = "AND (:targetId IS NULL OR p.id = :targetId) AND p.id > :afterId "
            + "AND NOT EXISTS (SELECT 1 FROM reconciliation_issue ri WHERE ri.issue_type = :issueType "
            + "AND ri.dedupe_key = CONCAT(:dedupePrefix, p.id)) "
            + "ORDER BY p.id LIMIT :limit";

    /** 주문에 구매확정 품목이 있는지(결제 후보 조건 조각). */
    String CONFIRMED_ITEM_EXISTS = "EXISTS (SELECT 1 FROM order_item ci WHERE ci.order_id = p.order_id AND ci.item_status = 'CONFIRMED') ";

    /** U5: 결제 CANCELLED인데 완료 환불이 없고 주문 품목에 클레임도 없다. */
    @Query(value = PAYMENT_SELECT
            + "WHERE p.status = 'CANCELLED' AND rs.payment_id IS NULL "
            + "AND NOT EXISTS (SELECT 1 FROM claim c JOIN order_item oi ON oi.id = c.order_item_id WHERE oi.order_id = p.order_id) "
            + PAYMENT_TAIL, nativeQuery = true)
    List<ReconciliationCandidate> findCancelledPaymentsWithoutRefund(
            @Param("issueType") String issueType,
            @Param("dedupePrefix") String dedupePrefix,
            @Param("targetId") Long targetId,
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    /** U6: 완료 환불 합계 = 결제액인데 결제가 PAID로 남았다(구매확정 품목 없는 경우 — 있으면 U7). */
    @Query(value = PAYMENT_SELECT
            + "WHERE p.status = 'PAID' AND p.amount > 0 AND rs.refunded = p.amount AND NOT " + CONFIRMED_ITEM_EXISTS
            + PAYMENT_TAIL, nativeQuery = true)
    List<ReconciliationCandidate> findFullyRefundedPaidPayments(
            @Param("issueType") String issueType,
            @Param("dedupePrefix") String dedupePrefix,
            @Param("targetId") Long targetId,
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    /** U7: 완료 환불 합계 = 결제액인데 주문에 구매확정 품목이 있다(결제 상태 무관). */
    @Query(value = PAYMENT_SELECT
            + "WHERE p.amount > 0 AND rs.refunded = p.amount AND " + CONFIRMED_ITEM_EXISTS
            + PAYMENT_TAIL, nativeQuery = true)
    List<ReconciliationCandidate> findFullyRefundedPaymentsWithConfirmedItem(
            @Param("issueType") String issueType,
            @Param("dedupePrefix") String dedupePrefix,
            @Param("targetId") Long targetId,
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    /**
     * U10·U11: 클레임이 {@code claimStatus}(COMPLETED·REJECTED)인데 품목이 아직 {@code staleItemStatuses}(요청 상태)에 머문다. 같은 품목에
     * 진행 중 클레임(REQUESTED·APPROVED)이 있으면 그 클레임의 요청 상태이므로 제외한다.
     */
    @Query(value = "SELECT c.id AS targetId, oi.order_id AS orderId, NULL AS paymentId, c.id AS claimId, NULL AS deliveryId, "
            + "oi.id AS orderItemId, NULL AS pgTid, NULL AS paymentStatus, NULL AS paymentAmount, NULL AS refundedAmount, "
            + "oi.item_status AS itemStatus, c.type AS claimType, c.status AS claimStatus, NULL AS deliveryStatus "
            + "FROM claim c JOIN order_item oi ON oi.id = c.order_item_id "
            + "WHERE c.status = :claimStatus AND oi.item_status IN (:staleItemStatuses) "
            + "AND NOT EXISTS (SELECT 1 FROM claim active WHERE active.order_item_id = oi.id "
            + "AND active.status IN ('REQUESTED', 'APPROVED')) "
            + "AND (:targetId IS NULL OR c.id = :targetId) AND c.id > :afterId "
            + "AND NOT EXISTS (SELECT 1 FROM reconciliation_issue ri WHERE ri.issue_type = :issueType "
            + "AND ri.dedupe_key = CONCAT(:dedupePrefix, c.id)) "
            + "ORDER BY c.id LIMIT :limit", nativeQuery = true)
    List<ReconciliationCandidate> findClaimsWithStaleItem(
            @Param("issueType") String issueType,
            @Param("dedupePrefix") String dedupePrefix,
            @Param("claimStatus") String claimStatus,
            @Param("staleItemStatuses") Collection<String> staleItemStatuses,
            @Param("targetId") Long targetId,
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    /**
     * U12·U13: 원 발송(OUTBOUND·claim_id NULL — 교환품 발송 제외)이 {@code deliveryStatus}(SHIPPING·DELIVERED)인데 품목이 그 이전 상태
     * {@code staleItemStatuses}에 머문다.
     */
    @Query(value = "SELECT d.id AS targetId, oi.order_id AS orderId, NULL AS paymentId, NULL AS claimId, d.id AS deliveryId, "
            + "oi.id AS orderItemId, NULL AS pgTid, NULL AS paymentStatus, NULL AS paymentAmount, NULL AS refundedAmount, "
            + "oi.item_status AS itemStatus, NULL AS claimType, NULL AS claimStatus, d.status AS deliveryStatus "
            + "FROM delivery d JOIN order_item oi ON oi.id = d.order_item_id "
            + "WHERE d.direction = 'OUTBOUND' AND d.claim_id IS NULL AND d.status = :deliveryStatus "
            + "AND oi.item_status IN (:staleItemStatuses) "
            + "AND (:targetId IS NULL OR d.id = :targetId) AND d.id > :afterId "
            + "AND NOT EXISTS (SELECT 1 FROM reconciliation_issue ri WHERE ri.issue_type = :issueType "
            + "AND ri.dedupe_key = CONCAT(:dedupePrefix, d.id)) "
            + "ORDER BY d.id LIMIT :limit", nativeQuery = true)
    List<ReconciliationCandidate> findOutboundDeliveriesWithStaleItem(
            @Param("issueType") String issueType,
            @Param("dedupePrefix") String dedupePrefix,
            @Param("deliveryStatus") String deliveryStatus,
            @Param("staleItemStatuses") Collection<String> staleItemStatuses,
            @Param("targetId") Long targetId,
            @Param("afterId") long afterId,
            @Param("limit") int limit);
}
