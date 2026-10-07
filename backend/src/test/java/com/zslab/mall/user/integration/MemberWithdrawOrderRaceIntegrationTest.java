package com.zslab.mall.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import com.zslab.mall.user.service.UserService;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 탈퇴와 주문 생성 경합(PF-08) 통합 테스트. 두 경로가 같은 구매자 행 락으로 줄 서야 "미결제 주문을 가진 탈퇴 회원"이 생기지 않는다.
 *
 * <ul>
 *   <li>W1·W2 주문 선행: 별도 트랜잭션이 구매자 행을 잠그고 미결제 주문을 넣은 채 쥐고 있으면 탈퇴(셀프·관리자)는 끝나지 않고 기다린다 →
 *   그 트랜잭션 커밋 뒤 탈퇴는 진행 중 주문을 보고 409로 끝난다.</li>
 *   <li>W3 탈퇴 선행: 탈퇴 트랜잭션이 커밋 전일 때 들어온 주문 요청(인증 필터는 아직 탈퇴 전 상태를 본다)은 구매자 행 락에서 기다린다 →
 *   탈퇴 커밋 뒤 락을 얻은 주문 생성은 탈퇴를 다시 확인해 409로 끝나고 주문·예약이 남지 않는다.</li>
 * </ul>
 *
 * <p>행 락 관찰을 위해 클래스에 {@code @Transactional}을 두지 않는다. 스케줄러 속성 집합은 {@code UnpaidOrderLimitConcurrencyIntegrationTest}와
 * 같게 둬 컨텍스트를 공유한다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zslab.order.auto-confirm.enabled=false",
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false"
})
class MemberWithdrawOrderRaceIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER = 6491L;
    private static final long ADMIN = 6492L;
    private static final long SELLER_ID = 6491L;
    private static final long PRODUCT_ID = 6491L;
    private static final long VARIANT_ID = 6491L;
    private static final long INVENTORY_ID = 6491L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 6491L;
    private static final long HELD_ORDER_ID = 6495L;
    private static final String BUYER_PID = pid("usr_", "PF08USR");
    private static final int INITIAL_STOCK = 10;
    private static final long RACE_TIMEOUT_SECONDS = 30L;
    /** 락 대기 판정 시간: 락이 없다면 요청은 이 안에 끝난다. 앱 행 락 대기 상한(5초)보다 충분히 짧다. */
    private static final long LOCK_WAIT_PROBE_MILLIS = 500L;
    private static final String ORDER_BODY = """
            {
              "items": [ { "productId": "%s", "variantId": "%s", "quantity": 1 } ],
              "shippingAddress": {
                "recipientName": "홍길동", "recipientPhone": "010-1234-5678",
                "zonecode": "06236", "addressRoad": "서울 강남대로 1"
              },
              "method": "CARD"
            }
            """.formatted(pid("prd_", "PF08PRD"), pid("var_", "PF08VAR"));

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private UserService userService;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seed();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("W1 주문 선행(구매자 행 락 + 미결제 주문 보유) 중 셀프 탈퇴 → 대기 → 커밋 뒤 409 MEMBER_ACTIVITY_IN_PROGRESS·탈퇴 안 됨")
    void selfWithdraw_waitsForOrderTransaction_thenRejected() throws Exception {
        String outcome = withdrawWhileOrderHeld(post("/api/v1/users/me/withdraw").with(authHeaders.buyer(BUYER)));

        assertThat(outcome).isEqualTo("409 MEMBER_ACTIVITY_IN_PROGRESS");
        assertThat(withdrawnAtIsNull()).as("탈퇴 미반영").isTrue();
    }

    @Test
    @DisplayName("W2 주문 선행 중 관리자 탈퇴 → 대기 → 커밋 뒤 409 MEMBER_ACTIVITY_IN_PROGRESS·탈퇴 안 됨")
    void adminWithdraw_waitsForOrderTransaction_thenRejected() throws Exception {
        String outcome = withdrawWhileOrderHeld(
                post("/api/v1/admin/members/" + BUYER_PID + "/withdraw").with(authHeaders.admin(ADMIN)));

        assertThat(outcome).isEqualTo("409 MEMBER_ACTIVITY_IN_PROGRESS");
        assertThat(withdrawnAtIsNull()).as("탈퇴 미반영").isTrue();
    }

    @Test
    @DisplayName("W3 탈퇴 선행(커밋 전) 중 주문 생성 → 대기 → 탈퇴 커밋 뒤 409 MEMBER_ALREADY_WITHDRAWN·주문 0·예약 0")
    void createOrder_waitsForWithdraw_thenRejected() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch withdrawn = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Void> holder = pool.submit(() -> {
                tx.executeWithoutResult(status -> {
                    userService.withdraw(BUYER);
                    withdrawn.countDown();
                    awaitQuietly(release);
                });
                return null;
            });
            assertThat(withdrawn.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("탈퇴 트랜잭션 진행(커밋 전)").isTrue();

            Future<String> order = pool.submit(outcomeOf(post("/api/v1/orders").with(authHeaders.buyer(BUYER))
                    .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY)));
            assertThatThrownBy(() -> order.get(LOCK_WAIT_PROBE_MILLIS, TimeUnit.MILLISECONDS))
                    .as("탈퇴 트랜잭션이 커밋 전인 동안 주문 생성은 끝나지 않는다(구매자 행 락 대기)")
                    .isInstanceOf(TimeoutException.class);

            release.countDown();
            holder.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(order.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("409 MEMBER_ALREADY_WITHDRAWN");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE buyer_id = ?", Integer.class, BUYER))
                .as("주문 미생성").isZero();
        assertThat(jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID))
                .as("예약 없음").isZero();
    }

    // ---------- 경합 실행 ----------

    /** 별도 트랜잭션이 구매자 행을 잠그고 미결제 주문을 넣은 채 쥔 동안 탈퇴 요청이 기다리는지 보고, 커밋 뒤 탈퇴 결과를 돌려준다. */
    private String withdrawWhileOrderHeld(MockHttpServletRequestBuilder withdrawRequest) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Void> holder = pool.submit(() -> {
                tx.executeWithoutResult(status -> {
                    jdbc.queryForList("SELECT id FROM `user` WHERE id = ? FOR UPDATE", BUYER);
                    jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, "
                                    + "shipping_fee, created_at, updated_at) "
                                    + "VALUES (?, ?, ?, 'ORDPF08HELD', 'PENDING_PAYMENT', 10000, 0, 0, NOW(6), NOW(6))",
                            HELD_ORDER_ID, pid("ord_", "PF08HELD"), BUYER);
                    locked.countDown();
                    awaitQuietly(release);
                });
                return null;
            });
            assertThat(locked.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("주문 트랜잭션이 구매자 행 락을 잡았다").isTrue();

            Future<String> withdraw = pool.submit(outcomeOf(withdrawRequest));
            assertThatThrownBy(() -> withdraw.get(LOCK_WAIT_PROBE_MILLIS, TimeUnit.MILLISECONDS))
                    .as("주문 트랜잭션이 락을 쥔 동안 탈퇴는 끝나지 않는다(구매자 행 락 대기)")
                    .isInstanceOf(TimeoutException.class);

            release.countDown();
            holder.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return withdraw.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** 요청 결과를 "상태" 또는 "상태 code" 문자열로 돌려주는 작업. */
    private Callable<String> outcomeOf(MockHttpServletRequestBuilder request) {
        return () -> {
            MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
            if (response.getStatus() < HttpStatus.BAD_REQUEST.value()) {
                return String.valueOf(response.getStatus());
            }
            String code = JsonPath.read(response.getContentAsString(StandardCharsets.UTF_8), "$.code");
            return response.getStatus() + " " + code;
        };
    }

    private boolean withdrawnAtIsNull() {
        return jdbc.queryForObject("SELECT withdrawn_at IS NULL FROM `user` WHERE id = ?", Boolean.class, BUYER);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("락 보유 래치 대기 중 인터럽트", exception);
        }
    }

    // ---------- seed·helpers ----------
    // 모든 시드·정리 SQL은 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seed() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) "
                                + "VALUES (?, ?, 'pf08@example.test', '탈퇴경합구매자', '010-6490-6491', NOW(6), NOW(6))",
                        BUYER, BUYER_PID);
                jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = 'BUYER'",
                        BUYER);
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '탈퇴경합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "PF08SLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, '탈퇴경합상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "PF08PRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCPF08', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "PF08VAR"), PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                                + "created_at, updated_at) VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))",
                        INVENTORY_ID, VARIANT_ID, INITIAL_STOCK, INITIAL_STOCK);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'USER' AND target_id = ?", BUYER);
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", BUYER);
                jdbc.update("DELETE FROM payment WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", BUYER);
                jdbc.update("DELETE FROM order_shipping_snapshot WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", BUYER);
                jdbc.update("DELETE FROM order_item WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", BUYER);
                jdbc.update("DELETE FROM `order` WHERE buyer_id = ?", BUYER);
                jdbc.update("DELETE FROM order_idempotency_key WHERE buyer_id = ?", BUYER);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM user_role WHERE user_id = ?", BUYER);
                jdbc.update("DELETE FROM `user` WHERE id = ?", BUYER);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
