package com.zslab.mall.order.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.order.scheduler.ExpiredOrderCleanupScheduler;
import com.zslab.mall.order.service.ExpiredOrderCleanupService;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만료 주문 정리 배치 기아 회귀(검수 5단계·D-175). 삭제 불가 주문(delivery·claim 손자·PENDING 결제)이 updated_at 오름차순 배치
 * 선두 100건을 점유하면 뒤의 정상 삭제 대상이 영구히 처리되지 않던 구조를, 조회 단계 NOT EXISTS 제외로 막았는지 검증한다.
 *
 * <p>시드는 FK 비활성·고정 id 범위(990000~)로 상위 그래프(user·product)를 생략한다. 스케줄러 빈은 킬스위치로 비활성이므로 직접
 * 생성해 1회 실행한다.
 */
@TestPropertySource(properties = "zslab.order.expired-cleanup.enabled=false")
class ExpiredOrderCleanupStarvationIntegrationTest extends AbstractIntegrationTest {

    private static final long BASE_ID = 990_000L;
    private static final int UNDELETABLE_WITH_DELIVERY = 40;
    private static final int UNDELETABLE_WITH_CLAIM = 40;
    private static final int UNDELETABLE_WITH_PENDING_PAYMENT = 40;
    private static final int UNDELETABLE_COUNT = UNDELETABLE_WITH_DELIVERY + UNDELETABLE_WITH_CLAIM + UNDELETABLE_WITH_PENDING_PAYMENT;
    private static final int DELETABLE_COUNT = 5;
    private static final int TOTAL = UNDELETABLE_COUNT + DELETABLE_COUNT;
    private static final int BATCH_SIZE = 100;
    /** 유예(7일) 경과를 보장하는 updated_at 기준 일수. 삭제 불가 건이 정상 건보다 오래돼 정렬 선두를 차지하도록 정상 건은 하루 뒤로 둔다. */
    private static final int UNDELETABLE_AGE_DAYS = 10;
    private static final int DELETABLE_AGE_DAYS = 9;

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ExpiredOrderCleanupService expiredOrderCleanupService;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("삭제 불가 만료 주문 120건(선두) + 정상 5건 → 1회 실행에 정상 5건 삭제·삭제 불가 건은 조회 자체에서 제외·잔존")
    void cleanupBatch_skipsUndeletableAtQueryLevel_deletesEligible() {
        assertThat(applicationContext.getBeanNamesForType(ExpiredOrderCleanupScheduler.class)).isEmpty();
        seedAll();

        LocalDateTime threshold = LocalDateTime.now().minusDays(7);
        List<Long> candidates = orderRepository.findExpiredCleanupCandidateIds(
                OrderStatus.PAYMENT_EXPIRED, PaymentStatus.PENDING, threshold, PageRequest.of(0, BATCH_SIZE));
        assertThat(candidates).hasSize(DELETABLE_COUNT);
        assertThat(candidates).allMatch(id -> id >= BASE_ID + UNDELETABLE_COUNT);

        new ExpiredOrderCleanupScheduler(orderRepository, expiredOrderCleanupService).cleanupBatch();

        assertThat(orderCount(BASE_ID + UNDELETABLE_COUNT, BASE_ID + TOTAL)).isZero();          // 정상 5건 삭제
        assertThat(orderCount(BASE_ID, BASE_ID + UNDELETABLE_COUNT)).isEqualTo(UNDELETABLE_COUNT); // 삭제 불가 120건 잔존
        assertThat(orderItemCount(BASE_ID, BASE_ID + UNDELETABLE_COUNT)).isEqualTo(UNDELETABLE_COUNT);
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedAll() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (int index = 0; index < TOTAL; index++) {
                    long id = BASE_ID + index;
                    boolean deletable = index >= UNDELETABLE_COUNT;
                    seedOrderWithItem(id, deletable ? DELETABLE_AGE_DAYS : UNDELETABLE_AGE_DAYS);
                    if (index < UNDELETABLE_WITH_DELIVERY) {
                        seedDelivery(id);
                    } else if (index < UNDELETABLE_WITH_DELIVERY + UNDELETABLE_WITH_CLAIM) {
                        seedClaim(id);
                    } else if (index < UNDELETABLE_COUNT) {
                        seedPayment(id, "PENDING");
                    } else {
                        seedPayment(id, "EXPIRED");
                    }
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedOrderWithItem(long id, int ageDays) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'PAYMENT_EXPIRED', 20000, 0, 0, NOW(6) - INTERVAL ? DAY, NOW(6) - INTERVAL ? DAY)",
                id, pid("ord_", "S5ORD" + id), id, "ORDS5" + id, ageDays, ageDays);
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                        + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 2, 10000, 20000, 'ORDERED', NOW(6), NOW(6), '기아 테스트 상품', 1000)",
                id, pid("oit_", "S5OIT" + id), id, id, id, id);
    }

    private void seedDelivery(long id) {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, carrier, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CJ', 'READY', NOW(6), NOW(6))",
                id, pid("dlv_", "S5DLV" + id), id);
    }

    private void seedClaim(long id) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                        + "requested_by, requested_at, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CANCEL', 'BUYER_CHANGED_MIND', 'REQUESTED', 'PAID', ?, NOW(6), 0, NOW(6), NOW(6))",
                id, pid("clm_", "S5CLM" + id), id, id);
    }

    private void seedPayment(long id, String status) {
        jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, payment_attempt_key, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CARD', 20000, ?, ?, NOW(6), NOW(6))",
                id, pid("pay_", "S5PAY" + id), id, status, pid("pat_", "S5PAT" + id));
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                long from = BASE_ID;
                long to = BASE_ID + TOTAL;
                jdbc.update("DELETE FROM delivery WHERE id >= ? AND id < ?", from, to);
                jdbc.update("DELETE FROM claim WHERE id >= ? AND id < ?", from, to);
                jdbc.update("DELETE FROM payment WHERE id >= ? AND id < ?", from, to);
                jdbc.update("DELETE FROM order_item WHERE id >= ? AND id < ?", from, to);
                jdbc.update("DELETE FROM `order` WHERE id >= ? AND id < ?", from, to);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private int orderCount(long fromInclusive, long toExclusive) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE id >= ? AND id < ?", Integer.class, fromInclusive, toExclusive);
    }

    private int orderItemCount(long fromInclusive, long toExclusive) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_item WHERE id >= ? AND id < ?", Integer.class, fromInclusive, toExclusive);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
