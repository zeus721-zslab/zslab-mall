package com.zslab.mall.payment.repository;

import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.support.AbstractDataJpaTest;
import java.time.LocalDateTime;

/**
 * Payment Aggregate @DataJpaTest 공통 베이스(Track B {@code OrderDataJpaTestBase} 패턴 준용).
 *
 * <p>Track 63: 슬라이스·싱글톤 컨테이너·TestEntityManager·FK 복원은 {@link AbstractDataJpaTest}로 승격.
 * 본 클래스는 payment 외부 FK 비활성 헬퍼와 결제 빌더만 보유한다.
 *
 * <p><b>FK 체크</b>: payment.order_id FK 상위(order·user 등)는 Track 7/Track B 소관이라 본 트랙에서 영속할 수 없으므로
 * {@link #disableForeignKeyChecks()}로 비활성화한다. UNIQUE·CHECK 제약은 FK와 무관하게 그대로 검증된다.
 */
abstract class PaymentDataJpaTestBase extends AbstractDataJpaTest {

    /** payment 외부 FK(order_id) 검증을 비활성화한다(상위 그래프는 별도 트랙). 영속 직전 호출한다. */
    protected void disableForeignKeyChecks() {
        entityManager.getEntityManager()
                .createNativeQuery("SET FOREIGN_KEY_CHECKS = 0")
                .executeUpdate();
    }

    /** PENDING 결제 1건을 구성한다(미영속). attempt_key는 호출자가 유일하게 지정한다. */
    protected Payment buildPayment(Long orderId, String attemptKey) {
        return Payment.create(orderId, PaymentMethod.CARD, 10_000L, attemptKey, LocalDateTime.now().plusMinutes(30));
    }
}
