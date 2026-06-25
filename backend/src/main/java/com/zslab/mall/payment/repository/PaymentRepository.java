package com.zslab.mall.payment.repository;

import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 결제 Repository(JpaRepository 단일·메서드 이름 쿼리만).
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPublicId(String publicId);

    /** 콜백 매핑 1차 키로 결제 행을 조회한다(D-35). */
    Optional<Payment> findByPaymentAttemptKey(String paymentAttemptKey);

    /** PAY-3a 가드: 한 주문에 특정 상태(PAID) 행 존재 여부. */
    boolean existsByOrderIdAndStatus(Long orderId, PaymentStatus status);

    /** 한 주문의 특정 상태 최신 행(재시도 TTL 판정·PAID 조회용). PAY-3a로 PAID는 ≤1건이나 안전하게 최신 1건만 취한다. */
    Optional<Payment> findFirstByOrderIdAndStatusOrderByIdDesc(Long orderId, PaymentStatus status);

    /** 운영 조회: 한 주문의 전체 결제 행(최신순). */
    List<Payment> findAllByOrderIdOrderByIdDesc(Long orderId);
}
