package com.zslab.mall.refund.repository;

import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.enums.RefundStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 환불 Repository(JpaRepository 단일·메서드 이름 쿼리 + 누적 합산 @Query).
 */
public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByPublicId(String publicId);

    /** webhook 콜백 매칭 키로 환불 행을 조회한다(RFN-3 멱등 키·expected-spec §6). */
    Optional<Refund> findByPgRefundId(String pgRefundId);

    /** 한 클레임의 환불 행 전체(재시도 = 새 행·RFN-2 추적). */
    List<Refund> findByClaimId(Long claimId);

    /**
     * 한 클레임에 주어진 status 집합 중 하나인 환불 행이 존재하는지 여부를 반환한다(파생 쿼리).
     *
     * <p>모든 변수는 메서드 이름 쿼리의 바인딩 파라미터로 전달되며 SQL injection 위험이 없다.
     */
    boolean existsByClaimIdAndStatusIn(Long claimId, Collection<RefundStatus> statuses);

    /**
     * 한 클레임에 주어진 status의 환불 행이 존재하는지 여부를 반환한다(파생 쿼리·D-115 결정3). EXCHANGE 차액환불
     * 종결 판정({@code ClaimService.tryCompleteExchange})에서 {@code status=COMPLETED} 존재 여부 확인에 사용한다.
     *
     * <p>모든 변수는 메서드 이름 쿼리의 바인딩 파라미터로 전달되며 SQL injection 위험이 없다.
     */
    boolean existsByClaimIdAndStatus(Long claimId, RefundStatus status);

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
     * <p>{@code status}는 enum 바인딩 파라미터로 전달한다(@Enumerated(STRING) 정합·JPQL enum 리터럴 ordinal 비교 함정 회피).
     * 모든 변수는 :status·:periodStart·:periodEnd 바인딩만 사용하며 SQL injection 위험이 없다.
     */
    @Query("SELECT oi.sellerId AS sellerId, COALESCE(SUM(r.amount), 0) AS refundAmount "
            + "FROM Refund r, Claim c, OrderItem oi "
            + "WHERE r.claimId = c.id "
            + "AND c.orderItemId = oi.id "
            + "AND r.status = :status "
            + "AND r.refundedAt >= :periodStart "
            + "AND r.refundedAt <= :periodEnd "
            + "GROUP BY oi.sellerId")
    List<SellerRefundProjection> aggregateRefundBySeller(
            @Param("status") RefundStatus status,
            @Param("periodStart") LocalDateTime periodStart,
            @Param("periodEnd") LocalDateTime periodEnd);
}
