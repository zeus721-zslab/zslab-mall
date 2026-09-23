package com.zslab.mall.refund.repository;

import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.enums.RefundStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 환불 Repository(JpaRepository 단일·메서드 이름 쿼리 + 누적 합산 @Query).
 */
public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByPublicId(String publicId);

    /** webhook 콜백 매칭 키로 환불 행을 조회한다(RFN-3 멱등 키·expected-spec §6). */
    Optional<Refund> findByPgRefundId(String pgRefundId);

    /**
     * 환불이 속한 주문 id(Track 104-1 D-215·주문 쓰기 락 대상 해소·콜백 매칭 키 기준). 엔티티를 적재하지 않는 스칼라 조회라 주문 락
     * 전에 불러도 1차 캐시에 환불이 남지 않는다. 모든 변수는 :pgRefundId 바인딩이다(SQL injection 위험 없음).
     */
    @Query("SELECT p.orderId FROM Refund r, Payment p WHERE p.id = r.paymentId AND r.pgRefundId = :pgRefundId")
    Optional<Long> findOrderIdByPgRefundId(@Param("pgRefundId") String pgRefundId);

    /** 한 클레임의 환불 행 전체(재시도 = 새 행·RFN-2 추적). */
    List<Refund> findByClaimId(Long claimId);

    /**
     * 활성 환불 행({@link RefundedCondition} — PENDING·COMPLETED + PG 성공이 기록된 FAILED·Track 104-3a)을 잠금 조회한다(D-172 initiate
     * 멱등 게이트). 잠금 읽기(SELECT ... FOR UPDATE)는 REPEATABLE READ
     * 스냅샷을 쓰지 않고 최신 커밋을 읽으므로, 클레임 행 락을 기다린 두 번째 initiate가 첫 번째가 만든 행을 반드시 본다(비잠금 exists 쿼리는
     * 트랜잭션 첫 읽기 시점 스냅샷에 묶여 못 보는 트랩). 모든 변수는 :name 바인딩 사용, SQL injection 위험 없음.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Refund r WHERE r.claimId = :claimId AND " + RefundedCondition.JPQL + " ORDER BY r.id ASC")
    List<Refund> findRefundedByClaimIdForUpdate(@Param("claimId") Long claimId);

    /**
     * 한 품목의 기환불액(Track 104-3a 품목 상한) — {@link RefundedCondition} 행의 amount 합(행이 없으면 0). 품목 귀속은
     * refund → claim → claim.orderItemId 경로다(refund에 품목 컬럼 없음). 모든 변수는 :orderItemId 바인딩, SQL injection 위험 없음.
     */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r, Claim c "
            + "WHERE r.claimId = c.id AND c.orderItemId = :orderItemId AND " + RefundedCondition.JPQL)
    long sumRefundedByOrderItemId(@Param("orderItemId") Long orderItemId);

    /** {@link #sumRefundedByOrderItemId}의 배치판(관리자 클레임 목록 재개시 판정·N+1 회피). 환불 행이 없는 품목은 결과에 없다(= 0). */
    @Query("SELECT c.orderItemId AS orderItemId, COALESCE(SUM(r.amount), 0) AS refundedAmount FROM Refund r, Claim c "
            + "WHERE r.claimId = c.id AND c.orderItemId IN :orderItemIds AND " + RefundedCondition.JPQL + " "
            + "GROUP BY c.orderItemId")
    List<OrderItemRefundedProjection> sumRefundedByOrderItemIdIn(@Param("orderItemIds") Collection<Long> orderItemIds);

    /**
     * Mock 완료 콜백 누락 복구(D-172): 생성 후 threshold 이전인 PENDING 환불(id 오름차순). pg_refund_id NULL 행(PG 요청 등록 전·initiate
     * 예외 잔존)은 콜백 키가 없어 재발생 불가하므로 조회에서 제외한다(D-175·id 오름차순 배치 선두 점유 기아 방지).
     */
    List<Refund> findByStatusAndPgRefundIdIsNotNullAndCreatedAtLessThanEqualOrderByIdAsc(
            RefundStatus status, LocalDateTime threshold, Pageable pageable);

    /** 관리자 클레임 목록·사용자 응답 배치 enrich(Track 80 D-169·N+1 회피). 클레임별 최신 행이 앞에 오도록 id 내림차순. */
    List<Refund> findByClaimIdInOrderByIdDesc(Collection<Long> claimIds);

    /**
     * 한 클레임에 주어진 status 집합 중 하나인 환불 행이 존재하는지 여부를 반환한다(파생 쿼리).
     *
     * <p>모든 변수는 메서드 이름 쿼리의 바인딩 파라미터로 전달되며 SQL injection 위험이 없다.
     */
    boolean existsByClaimIdAndStatusIn(Long claimId, Collection<RefundStatus> statuses);

    /**
     * 한 클레임에 활성(PENDING·COMPLETED) 환불 행이 존재하는지 여부(D-94 Q6 멱등 게이트·공개 계약). FAILED는
     * 활성에서 제외해 RFN-2(재시도 = 새 행)와 충돌하지 않는다. 호출부는 본 메서드만 사용한다.
     */
    default boolean existsActiveByClaimId(Long claimId) {
        return existsByClaimIdAndStatusIn(claimId, Set.of(RefundStatus.PENDING, RefundStatus.COMPLETED));
    }

    /**
     * 한 결제에 대한 COMPLETED 환불 금액 누적 합을 반환한다(PAY-1 검증·교차 Aggregate). COALESCE로 행이 없으면 0.
     *
     * <p>status는 enum 바인딩 파라미터로 전달한다(@Enumerated(STRING) 정합·JPQL enum 리터럴의 ordinal 비교 함정 회피).
     * 모든 변수는 :paymentId·:status 바인딩만 사용하며 SQL injection 위험이 없다.
     */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r "
            + "WHERE r.paymentId = :paymentId AND r.status = :status")
    long sumAmountByPaymentIdAndStatus(@Param("paymentId") Long paymentId, @Param("status") RefundStatus status);

    /** PAY-1 누적 검증용: COMPLETED 환불 금액 합(공개 계약·호출부는 본 메서드만 사용). */
    default long sumCompletedByPaymentId(Long paymentId) {
        return sumAmountByPaymentIdAndStatus(paymentId, RefundStatus.COMPLETED);
    }

    /**
     * 정산 기간 내 완료(COMPLETED) 환불액(amount 합)을 seller별로 집계한다(Track 48 P2·정산 refund 소스). refund에는
     * seller_id 직접 컬럼이 없으므로 refund→claim→order_item 경로로 seller에 귀속한다. Claim·OrderItem은 Long ID 참조
     * (@ManyToOne 미적용·aggregate-boundary)이므로 연관 탐색 조인이 불가해 theta-join(FK = id 조건)으로 연결한다.
     * 기간 기준은 {@code refunded_at}이며 경계는 양끝 포함({@code >= periodStart AND <= periodEnd})이다.
     *
     * <p><b>gross 대상 한정(Track 79 D-168·B)</b>: {@code oi.confirmedAt IS NOT NULL} — 매출(gross)에 집계된 적이 있는(구매확정)
     * 품목의 환불만 차감한다. 확정 전 취소(CANCEL)·반품(RETURN)·교환 차액 환불은 품목이 gross에 포함된 적이 없어 차감하면 이중 차감이다.
     * 기준을 Claim type이 아니라 "gross 포함 여부(confirmed_at)"로 둔 이유: 정산식 net = gross − refund의 대칭 조건이며, 향후
     * 확정 후 반품 전이가 열려도 그대로 정합하다.
     *
     * <p>{@code status}는 enum 바인딩 파라미터로 전달한다(@Enumerated(STRING) 정합·JPQL enum 리터럴 ordinal 비교 함정 회피).
     * 모든 변수는 :status·:periodStart·:periodEnd 바인딩만 사용하며 SQL injection 위험이 없다.
     */
    @Query("SELECT oi.sellerId AS sellerId, COALESCE(SUM(r.amount), 0) AS refundAmount "
            + "FROM Refund r, Claim c, OrderItem oi "
            + "WHERE r.claimId = c.id "
            + "AND c.orderItemId = oi.id "
            + "AND oi.confirmedAt IS NOT NULL "
            + "AND r.status = :status "
            + "AND r.refundedAt >= :periodStart "
            + "AND r.refundedAt <= :periodEnd "
            + "GROUP BY oi.sellerId")
    List<SellerRefundProjection> aggregateRefundBySeller(
            @Param("status") RefundStatus status,
            @Param("periodStart") LocalDateTime periodStart,
            @Param("periodEnd") LocalDateTime periodEnd);

    /**
     * 기간 말까지 완료(COMPLETED)됐고 아직 어느 정산에도 편입되지 않은 환불을 스냅샷 소스로 조회한다(Track 85·settlement_item REFUND ·
     * Track 104-3b 결정 ⑦). 조인 경로·D-168 가드({@code oi.confirmedAt IS NOT NULL})는 {@link #aggregateRefundBySeller}와 동일하다.
     * 기간 하한이 없으므로 앞선 기간에 빠진 환불(환불 뒤 늦은 확정·보류 해제·지급 후 환불)도 다음 정산의 차감으로 들어온다 — 편입 여부는
     * settlement_item (REFUND, source_id) 전역 UNIQUE 키로 판정한다. 열린(OPEN) 불일치가 있는 주문의 환불은 제외한다(보류·결정 ⑥).
     * sellerId가 null이면 전 셀러, 아니면 해당 셀러만(재생성). 모든 변수는 :status·:periodEnd·:sellerId 바인딩만 사용하며 SQL injection
     * 위험이 없다.
     */
    @Query("SELECT r.id AS refundId, r.amount AS amount, r.refundedAt AS refundedAt, oi.id AS orderItemId, "
            + "oi.sellerId AS sellerId, o.publicId AS orderPublicId, oi.productName AS productName, "
            + "oi.optionLabel AS optionLabel, oi.quantity AS quantity, oi.commissionRate AS commissionRate "
            + "FROM Refund r, Claim c, OrderItem oi JOIN oi.order o "
            + "WHERE r.claimId = c.id "
            + "AND c.orderItemId = oi.id "
            + "AND oi.confirmedAt IS NOT NULL "
            + "AND r.status = :status "
            + "AND r.refundedAt <= :periodEnd "
            + "AND (:sellerId IS NULL OR oi.sellerId = :sellerId) "
            + "AND NOT EXISTS (SELECT 1 FROM SettlementItem si "
            + "WHERE si.itemType = com.zslab.mall.settlement.enums.SettlementItemType.REFUND AND si.sourceId = r.id) "
            + "AND NOT EXISTS (SELECT 1 FROM ReconciliationIssue ri "
            + "WHERE ri.orderId = o.id AND ri.status = com.zslab.mall.reconciliation.enums.ReconciliationIssueStatus.OPEN) "
            + "ORDER BY oi.sellerId, r.refundedAt, r.id")
    List<SettlementRefundSourceProjection> findSettlementRefundSources(
            @Param("status") RefundStatus status,
            @Param("periodEnd") LocalDateTime periodEnd,
            @Param("sellerId") Long sellerId);
}
