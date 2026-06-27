package com.zslab.mall.refund.repository;

import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.enums.RefundStatus;
import java.util.List;
import java.util.Optional;
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
}
