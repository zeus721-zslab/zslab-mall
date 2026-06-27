package com.zslab.mall.payment.repository;

import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 결제 Repository(JpaRepository 단일·메서드 이름 쿼리 + 비관적 락 조회).
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPublicId(String publicId);

    /**
     * Payment 행을 비관적 쓰기 락(SELECT ... FOR UPDATE)으로 조회한다(Track 5·D-68). PAY-1 사후 재검증 시 동시 환불
     * 확정을 직렬화해 과환불을 차단한다. 모든 변수는 :id 바인딩이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    /** 콜백 매핑 1차 키로 결제 행을 조회한다(D-35). */
    Optional<Payment> findByPaymentAttemptKey(String paymentAttemptKey);

    /** PAY-3a 가드: 한 주문에 특정 상태(PAID) 행 존재 여부. */
    boolean existsByOrderIdAndStatus(Long orderId, PaymentStatus status);

    /** 한 주문의 특정 상태 최신 행(재시도 TTL 판정·PAID 조회용). PAY-3a로 PAID는 ≤1건이나 안전하게 최신 1건만 취한다. */
    Optional<Payment> findFirstByOrderIdAndStatusOrderByIdDesc(Long orderId, PaymentStatus status);

    /** 운영 조회: 한 주문의 전체 결제 행(최신순). */
    List<Payment> findAllByOrderIdOrderByIdDesc(Long orderId);
}
