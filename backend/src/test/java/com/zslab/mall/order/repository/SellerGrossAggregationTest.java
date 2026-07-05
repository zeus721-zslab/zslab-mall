package com.zslab.mall.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.order.enums.OrderItemStatus;
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
 * {@link OrderItemRepository#aggregateGrossBySeller} @DataJpaTest(Track 48 P2). 기간 내 CONFIRMED 품목의 seller별
 * total_price 합·기간 밖/비CONFIRMED/NULL confirmed_at 제외·GROUP BY 정확성을 검증한다. FK 부담 회피를 위해
 * FOREIGN_KEY_CHECKS=0 하에 order_item만 native 시드한다.
 *
 * <p>시드 시각은 반드시 {@code LocalDateTime} 바인딩 파라미터로 넣는다 — raw 문자열 리터럴로 넣으면 저장은 KST 원문
 * 그대로지만 조회 쿼리의 {@code LocalDateTime} 파라미터는 JDBC가 세션 타임존(-9h)으로 변환해 경계가 어긋난다. 양쪽을
 * 같은 바인딩 경로로 통일해 오프셋을 상쇄한다.
 */
class SellerGrossAggregationTest extends Batch1DataJpaTestBase {

    private static final long SELLER_A = 9101L;
    private static final long SELLER_B = 9102L;
    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2026, 6, 1, 0, 0, 0);
    private static final LocalDateTime PERIOD_END = LocalDateTime.of(2026, 6, 30, 23, 59, 59);

    @Autowired
    private OrderItemRepository orderItemRepository;

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

    private void insertOrderItem(long sellerId, long totalPrice, String itemStatus, LocalDateTime confirmedAt) {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO order_item "
            + "(public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
            + "item_status, confirmed_at, created_at, updated_at) "
            + "VALUES (:pid, 1, 1, 1, :seller, 1, :total, :total, :status, :confirmedAt, NOW(6), NOW(6))");
        query.setParameter("pid", String.format("oit_%026d", ++seq));
        query.setParameter("seller", sellerId);
        query.setParameter("total", totalPrice);
        query.setParameter("status", itemStatus);
        query.setParameter("confirmedAt", confirmedAt);
        query.executeUpdate();
    }

    private void insertOrderItemNullConfirmed(long sellerId, long totalPrice, String itemStatus) {
        disableFkChecks();
        // confirmed_at 컬럼 생략 → NULL 저장(미확정 표현).
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO order_item "
            + "(public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
            + "item_status, created_at, updated_at) "
            + "VALUES (:pid, 1, 1, 1, :seller, 1, :total, :total, :status, NOW(6), NOW(6))");
        query.setParameter("pid", String.format("oit_%026d", ++seq));
        query.setParameter("seller", sellerId);
        query.setParameter("total", totalPrice);
        query.setParameter("status", itemStatus);
        query.executeUpdate();
    }

    @Test
    @DisplayName("aggregateGrossBySeller: seller별 CONFIRMED total_price 합·기간밖/비CONFIRMED/NULL 제외·경계 포함")
    void aggregateGrossBySeller_groupsAndFilters() {
        // seller A: 시작·종료 경계 정각 CONFIRMED 2건(>= / <= 포함성) → 15000
        insertOrderItem(SELLER_A, 10_000L, "CONFIRMED", PERIOD_START);
        insertOrderItem(SELLER_A, 5_000L, "CONFIRMED", PERIOD_END);
        // seller A: 제외 대상 — 비CONFIRMED(기간 내)·기간 전 1초·기간 후 1초·NULL(미확정)
        insertOrderItem(SELLER_A, 999L, "DELIVERED", LocalDateTime.of(2026, 6, 15, 0, 0, 0));
        insertOrderItem(SELLER_A, 888L, "CONFIRMED", LocalDateTime.of(2026, 5, 31, 23, 59, 59));
        insertOrderItem(SELLER_A, 777L, "CONFIRMED", LocalDateTime.of(2026, 7, 1, 0, 0, 0));
        insertOrderItemNullConfirmed(SELLER_A, 666L, "CONFIRMED");
        // seller B: 기간 내 CONFIRMED 1건 → 7000
        insertOrderItem(SELLER_B, 7_000L, "CONFIRMED", LocalDateTime.of(2026, 6, 15, 12, 0, 0));
        entityManager.flush();
        entityManager.clear();

        List<SellerGrossProjection> result = orderItemRepository.aggregateGrossBySeller(
            OrderItemStatus.CONFIRMED, PERIOD_START, PERIOD_END);

        Map<Long, Long> grossBySeller = result.stream()
            .collect(Collectors.toMap(SellerGrossProjection::getSellerId, SellerGrossProjection::getGrossAmount));
        assertThat(grossBySeller).hasSize(2);
        assertThat(grossBySeller.get(SELLER_A)).isEqualTo(15_000L);
        assertThat(grossBySeller.get(SELLER_B)).isEqualTo(7_000L);
    }

    @Test
    @DisplayName("aggregateGrossBySeller: 대상 없으면 빈 결과(집계 행 0)")
    void aggregateGrossBySeller_emptyWhenNoMatch() {
        insertOrderItemNullConfirmed(SELLER_A, 1_000L, "DELIVERED");
        entityManager.flush();
        entityManager.clear();

        List<SellerGrossProjection> result = orderItemRepository.aggregateGrossBySeller(
            OrderItemStatus.CONFIRMED, PERIOD_START, PERIOD_END);

        assertThat(result).isEmpty();
    }
}
