package com.zslab.mall.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비관락 대기 초과의 GEH 409 매핑 통합 테스트(D-268 OPS-05). StandardErrorMappingIntegrationTest에서 분리했다 — 아래 lock wait
 * 단축 설정이 이 클래스의 테스트에만 적용되도록 한다.
 *
 * <p>T8은 비관락 대기 초과를 실제로 재현한다. 커넥션 init SQL에 {@code innodb_lock_wait_timeout=1}을 더해(기존
 * time_zone 설정 유지) 기본 50초 대기를 1초로 줄인다. 행 락 관찰을 위해 클래스에 {@code @Transactional}을 두지 않으며,
 * 커밋 시드와 겹치지 않도록 주문 계열 스케줄러를 끈다(OrderSerializationLockRaceIntegrationTest 관례).
 *
 * <p>T9는 {@code entityManager.refresh(PESSIMISTIC_WRITE)} 경로(클레임 요청의 품목 락)를 재현한다. 이 경로는 Spring 예외 변환을
 * 거치지 않아 jakarta 비관락 예외로 올라오므로 T8과 별도로 409 매핑을 확인한다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.hikari.connection-init-sql=SET time_zone = '+09:00', SESSION innodb_lock_wait_timeout = 1",
        "zslab.order.auto-confirm.enabled=false",
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false"
})
class LockTimeoutErrorMappingIntegrationTest extends AbstractIntegrationTest {

    private static final String ERROR_TYPE_BASE = "https://zslab-mall.duckdns.org/errors/";

    private static final long LOCK_BUYER_ID = 6391L;
    private static final long LOCK_SELLER_ID = 6391L;
    private static final long LOCK_PRODUCT_ID = 6391L;
    private static final long LOCK_VARIANT_ID = 6391L;
    private static final long LOCK_INVENTORY_ID = 6391L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long LOCK_DUMMY_FK_ID = 6391L;
    private static final long REFRESH_LOCK_ORDER_ID = 6392L;
    private static final long REFRESH_LOCK_ORDER_ITEM_ID = 6392L;
    /** T9 구매자. 주문 행의 buyer_id 값으로만 쓰이며 락 대기에서 실패해 FK가 필요한 쓰기에 닿지 않는다. */
    private static final long REFRESH_LOCK_BUYER_ID = 6392L;
    private static final int LOCK_RACE_TIMEOUT_SECONDS = 30;
    private static final String LOCK_ORDER_BODY = """
            {
              "items": [ { "productId": "%s", "variantId": "%s", "quantity": 1 } ],
              "shippingAddress": {
                "recipientName": "홍길동", "recipientPhone": "010-1234-5678",
                "zonecode": "06236", "addressRoad": "서울 강남대로 1"
              },
              "method": "CARD"
            }
            """.formatted(lockPid("prd_"), lockPid("var_"));

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUpTransactionTemplate() {
        tx = new TransactionTemplate(txManager);
    }

    @Test
    @DisplayName("T8 비관락 대기 초과(재고 행 FOR UPDATE 보유 중 주문 생성) → 409 LOCK_CONFLICT·고정 detail(수정 전 500 INTERNAL_ERROR)")
    void pessimisticLockTimeout_returns409LockConflict() throws Exception {
        seedLockTarget();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 트랜잭션 1: 재고 행 락을 쥐고 대기한다. 트랜잭션 2(주문 생성 요청)의 예약 FOR UPDATE가 1초 대기 후 1205로 실패한다.
            Future<Void> holder = pool.submit(() -> {
                tx.executeWithoutResult(status -> {
                    jdbc.queryForList("SELECT id FROM inventory WHERE variant_id = ? FOR UPDATE", LOCK_VARIANT_ID);
                    holding.countDown();
                    awaitQuietly(release);
                });
                return null;
            });
            assertThat(holding.await(LOCK_RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("락 보유 트랜잭션 진입").isTrue();

            mockMvc.perform(post("/api/v1/orders").with(authHeaders.buyer(LOCK_BUYER_ID))
                            .contentType(MediaType.APPLICATION_JSON).content(LOCK_ORDER_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LOCK_CONFLICT"))
                    .andExpect(jsonPath("$.detail").value("다른 처리와 겹쳤습니다. 잠시 후 다시 시도해 주세요."))
                    .andExpect(jsonPath("$.type").value(ERROR_TYPE_BASE + "lock-conflict"));

            release.countDown();
            holder.get(LOCK_RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE buyer_id = ?", Integer.class, LOCK_BUYER_ID))
                    .as("주문 TX 롤백").isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
            cleanupLockTarget();
        }
    }

    @Test
    @DisplayName("T9 refresh(PESSIMISTIC_WRITE) 대기 초과(품목 행 FOR UPDATE 보유 중 클레임 요청) → 409 LOCK_CONFLICT(수정 전 500 INTERNAL_ERROR)")
    void refreshPessimisticLockTimeout_returns409LockConflict() throws Exception {
        seedRefreshLockTarget();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 트랜잭션 1: 주문 품목 행 락만 쥔다(주문 행은 비워 둠). 클레임 요청은 주문 쓰기 락을 통과한 뒤
            // createClaim의 entityManager.refresh(품목, PESSIMISTIC_WRITE)에서 1초 대기 후 1205로 실패한다.
            Future<Void> holder = pool.submit(() -> {
                tx.executeWithoutResult(status -> {
                    jdbc.queryForList("SELECT id FROM order_item WHERE id = ? FOR UPDATE", REFRESH_LOCK_ORDER_ITEM_ID);
                    holding.countDown();
                    awaitQuietly(release);
                });
                return null;
            });
            assertThat(holding.await(LOCK_RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("락 보유 트랜잭션 진입").isTrue();

            mockMvc.perform(post("/api/v1/claims").with(authHeaders.buyer(REFRESH_LOCK_BUYER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "orderItemPublicId": "%s", "claimType": "CANCEL", "reasonCode": "BUYER_CHANGED_MIND" }
                                    """.formatted(refreshPid("oit_"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LOCK_CONFLICT"))
                    .andExpect(jsonPath("$.detail").value("다른 처리와 겹쳤습니다. 잠시 후 다시 시도해 주세요."))
                    .andExpect(jsonPath("$.type").value(ERROR_TYPE_BASE + "lock-conflict"));

            release.countDown();
            holder.get(LOCK_RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM claim WHERE order_item_id = ?", Integer.class,
                    REFRESH_LOCK_ORDER_ITEM_ID)).as("클레임 TX 롤백").isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
            cleanupRefreshLockTarget();
        }
    }

    // ---------- T9 seed ----------
    // 모든 시드 SQL은 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedRefreshLockTarget() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, "
                                + "shipping_fee, created_at, updated_at) VALUES (?, ?, ?, 'ORDLOCKREFRESH', 'PAID', 10000, 0, 0, NOW(6), NOW(6))",
                        REFRESH_LOCK_ORDER_ID, refreshPid("ord_"), REFRESH_LOCK_BUYER_ID);
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                                + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                                + "VALUES (?, ?, ?, 1, 1, 1, 1, 10000, 10000, 'PAID', NOW(6), NOW(6), '락상품', 1000)",
                        REFRESH_LOCK_ORDER_ITEM_ID, refreshPid("oit_"), REFRESH_LOCK_ORDER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanupRefreshLockTarget() {
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM claim WHERE order_item_id = ?", REFRESH_LOCK_ORDER_ITEM_ID);
            jdbc.update("DELETE FROM order_item WHERE id = ?", REFRESH_LOCK_ORDER_ITEM_ID);
            jdbc.update("DELETE FROM `order` WHERE id = ?", REFRESH_LOCK_ORDER_ID);
        });
    }

    private static String refreshPid(String prefix) {
        return prefix + "LOCKREFRESH000000000000000";
    }

    // ---------- T8 seed·helpers ----------
    // 모든 시드 SQL은 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedLockTarget() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) "
                                + "VALUES (?, ?, 'lock@example.test', '락구매자', '010-6300-6300', NOW(6), NOW(6))",
                        LOCK_BUYER_ID, lockPid("usr_"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '락셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        LOCK_SELLER_ID, lockPid("slr_"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, '락상품', 'SALE', 10000, NOW(6), NOW(6))",
                        LOCK_PRODUCT_ID, lockPid("prd_"), LOCK_SELLER_ID, LOCK_DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCLOCK', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        LOCK_VARIANT_ID, lockPid("var_"), LOCK_PRODUCT_ID, LOCK_DUMMY_FK_ID);
                jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                                + "created_at, updated_at) VALUES (?, ?, 10, 0, 10, NOW(6), NOW(6))",
                        LOCK_INVENTORY_ID, LOCK_VARIANT_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanupLockTarget() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM order_shipping_snapshot WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", LOCK_BUYER_ID);
                jdbc.update("DELETE FROM order_item WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", LOCK_BUYER_ID);
                jdbc.update("DELETE FROM `order` WHERE buyer_id = ?", LOCK_BUYER_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", LOCK_INVENTORY_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", LOCK_VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", LOCK_PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", LOCK_SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", LOCK_BUYER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(LOCK_RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("락 보유 래치 대기 중 인터럽트", exception);
        }
    }

    private static String lockPid(String prefix) {
        return prefix + "LOCKCONFLICT00000000000000";
    }
}
