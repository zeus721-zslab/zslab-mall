package com.zslab.mall.refund.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.refund.enums.RefundStatus;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link RefundRepository#aggregateRefundBySeller} @DataJpaTest(Track 48 P2·3-hop theta-join). refund→claim→order_item
 * 경로로 seller에 귀속한 COMPLETED·기간 내 환불액 합·비COMPLETED/기간 밖 제외·seller별 분리를 검증한다. FOREIGN_KEY_CHECKS=0
 * 하에 order_item·claim·refund를 FK 위상 순서로 native 시드하며, claim/refund의 부모 id는 LAST_INSERT_ID로 잇는다.
 *
 * <p>시각은 {@code LocalDateTime} 바인딩 파라미터로 시드한다 — raw 문자열 리터럴은 저장이 KST 원문 그대로지만 조회
 * 파라미터는 JDBC 세션 타임존(-9h) 변환을 거쳐 경계가 어긋나므로, 양쪽 바인딩 경로를 통일한다.
 */
class SellerRefundAggregationTest extends Batch1DataJpaTestBase {

    private static final long SELLER_A = 9201L;
    private static final long SELLER_B = 9202L;
    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2026, 6, 1, 0, 0, 0);
    private static final LocalDateTime PERIOD_END = LocalDateTime.of(2026, 6, 30, 23, 59, 59);

    @Autowired
    private RefundRepository refundRepository;

    private int seq = 0;

    private void disableFkChecks() {
        entityManager.getEntityManager().createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
    }

    @AfterEach
    void restoreForeignKeyChecks() {
        // SET FOREIGN_KEY_CHECKS는 세션 변수라 커밋/롤백과 무관하게 커넥션 풀로 누수된다(Batch1 공유 풀).
        // 미복구 시 다른 FK 위반 기대 테스트가 오염된 커넥션을 잡아 예외 미발생으로 실패한다 → 반드시 복구.
        entityManager.getEntityManager().createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
    }

    private long lastInsertId() {
        return ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private long insertOrderItem(long sellerId) {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO order_item "
            + "(public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
            + "item_status, created_at, updated_at) "
            + "VALUES (:pid, 1, 1, 1, :seller, 1, 1000, 1000, 'DELIVERED', NOW(6), NOW(6))");
        query.setParameter("pid", String.format("oit_%026d", ++seq));
        query.setParameter("seller", sellerId);
        query.executeUpdate();
        return lastInsertId();
    }

    private long insertClaim(long orderItemId) {
        disableFkChecks();
        // 집계는 refund.status·refunded_at만 필터하므로 claim 상태값은 임의(RETURN·DELIVERED 스냅샷)로 둔다.
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO claim "
            + "(public_id, order_item_id, type, reason_code, status, previous_order_item_status, created_at, updated_at) "
            + "VALUES (:pid, :orderItemId, 'RETURN', 'DEFECT', 'COMPLETED', 'DELIVERED', NOW(6), NOW(6))");
        query.setParameter("pid", String.format("clm_%026d", ++seq));
        query.setParameter("orderItemId", orderItemId);
        query.executeUpdate();
        return lastInsertId();
    }

    private void insertRefund(long claimId, long amount, String status, LocalDateTime refundedAt) {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO refund "
            + "(public_id, claim_id, payment_id, amount, status, refunded_at, created_at, updated_at) "
            + "VALUES (:pid, :claimId, 1, :amount, :status, :refundedAt, NOW(6), NOW(6))");
        query.setParameter("pid", String.format("rfn_%026d", ++seq));
        query.setParameter("claimId", claimId);
        query.setParameter("amount", amount);
        query.setParameter("status", status);
        query.setParameter("refundedAt", refundedAt);
        query.executeUpdate();
    }

    private void insertRefundNullRefunded(long claimId, long amount, String status) {
        disableFkChecks();
        // refunded_at 컬럼 생략 → NULL(PENDING은 미완료라 refunded_at 없음).
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO refund "
            + "(public_id, claim_id, payment_id, amount, status, created_at, updated_at) "
            + "VALUES (:pid, :claimId, 1, :amount, :status, NOW(6), NOW(6))");
        query.setParameter("pid", String.format("rfn_%026d", ++seq));
        query.setParameter("claimId", claimId);
        query.setParameter("amount", amount);
        query.setParameter("status", status);
        query.executeUpdate();
    }

    @Test
    @DisplayName("aggregateRefundBySeller: 3-hop 귀속·COMPLETED 기간내만 합산·비COMPLETED/기간밖 제외·seller별 분리")
    void aggregateRefundBySeller_joinsAndFilters() {
        long orderItemA = insertOrderItem(SELLER_A);
        long orderItemB = insertOrderItem(SELLER_B);
        long claimA1 = insertClaim(orderItemA);
        long claimA2 = insertClaim(orderItemA);
        long claimB1 = insertClaim(orderItemB);

        // seller A: COMPLETED 기간 내 2건(시작·종료 경계 정각) → 5000
        insertRefund(claimA1, 3_000L, "COMPLETED", PERIOD_START);
        insertRefund(claimA2, 2_000L, "COMPLETED", PERIOD_END);
        // seller B: COMPLETED 기간 내 1건 → 4000
        insertRefund(claimB1, 4_000L, "COMPLETED", LocalDateTime.of(2026, 6, 15, 12, 0, 0));
        // 제외: PENDING(status·refunded_at NULL)·COMPLETED 기간 후
        insertRefundNullRefunded(claimA1, 9_999L, "PENDING");
        insertRefund(claimA1, 8_888L, "COMPLETED", LocalDateTime.of(2026, 7, 1, 0, 0, 0));
        entityManager.flush();
        entityManager.clear();

        List<SellerRefundProjection> result = refundRepository.aggregateRefundBySeller(
            RefundStatus.COMPLETED, PERIOD_START, PERIOD_END);

        Map<Long, Long> refundBySeller = result.stream()
            .collect(Collectors.toMap(SellerRefundProjection::getSellerId, SellerRefundProjection::getRefundAmount));
        assertThat(refundBySeller).hasSize(2);
        assertThat(refundBySeller.get(SELLER_A)).isEqualTo(5_000L);
        assertThat(refundBySeller.get(SELLER_B)).isEqualTo(4_000L);
    }
}
