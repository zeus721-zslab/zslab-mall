package com.zslab.mall.settlement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.Batch1DataJpaTestBase;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link SettlementRepository#existsBySellerIdAndPeriodStartAndPeriodEnd} @DataJpaTest(Track 48 P2). 동일 seller·기간
 * 존재 시 true·미존재(다른 seller·다른 기간) 시 false를 검증한다. commission_rate는 DDL DEFAULT 1000이 적용되므로 생략한다.
 * FK 부담 회피를 위해 FOREIGN_KEY_CHECKS=0 하에 settlement만 native 시드한다.
 *
 * <p>기간은 {@code LocalDateTime} 바인딩 파라미터로 시드한다 — raw 문자열 리터럴은 저장이 KST 원문 그대로지만 파생 쿼리
 * 파라미터는 JDBC 세션 타임존(-9h) 변환을 거쳐 동등비교가 어긋난다. 저장·조회 바인딩 경로를 통일해 정확 매칭시킨다.
 */
class SettlementExistsByPeriodTest extends Batch1DataJpaTestBase {

    private static final long SELLER = 9301L;
    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2026, 6, 1, 0, 0, 0);
    private static final LocalDateTime PERIOD_END = LocalDateTime.of(2026, 6, 30, 23, 59, 59);

    @Autowired
    private SettlementRepository settlementRepository;

    @AfterEach
    void restoreForeignKeyChecks() {
        // SET FOREIGN_KEY_CHECKS는 세션 변수라 커밋/롤백과 무관하게 커넥션 풀로 누수된다(Batch1 공유 풀).
        // 미복구 시 다른 FK 위반 기대 테스트가 오염된 커넥션을 잡아 예외 미발생으로 실패한다 → 반드시 복구.
        entityManager.getEntityManager().createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
    }

    private void insertSettlement() {
        entityManager.getEntityManager().createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO settlement "
            + "(seller_id, bank_account_id, period_start, period_end, gross_amount, fee_amount, refund_amount, "
            + "net_amount, status, created_at, updated_at) "
            + "VALUES (:seller, 1, :periodStart, :periodEnd, 0, 0, 0, 0, 'PENDING', NOW(6), NOW(6))");
        query.setParameter("seller", SELLER);
        query.setParameter("periodStart", PERIOD_START);
        query.setParameter("periodEnd", PERIOD_END);
        query.executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("existsByPeriod: 동일 seller·기간 존재 → true")
    void exists_true_whenSamePeriod() {
        insertSettlement();

        boolean exists = settlementRepository.existsBySellerIdAndPeriodStartAndPeriodEnd(
            SELLER, PERIOD_START, PERIOD_END);

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("existsByPeriod: 다른 seller·다른 기간 → false")
    void exists_false_whenDifferentSellerOrPeriod() {
        insertSettlement();

        assertThat(settlementRepository.existsBySellerIdAndPeriodStartAndPeriodEnd(
            9999L, PERIOD_START, PERIOD_END)).isFalse();
        assertThat(settlementRepository.existsBySellerIdAndPeriodStartAndPeriodEnd(
            SELLER, LocalDateTime.of(2026, 5, 1, 0, 0, 0), PERIOD_END)).isFalse();
    }
}
