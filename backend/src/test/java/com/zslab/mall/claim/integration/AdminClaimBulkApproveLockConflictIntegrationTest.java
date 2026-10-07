package com.zslab.mall.claim.integration;

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
import org.junit.jupiter.api.AfterEach;
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
 * 관리자 클레임 일괄 승인의 비관락 대기 초과 항목화(PF-05) 통합 테스트. 둘째 클레임의 주문 행을 별도 트랜잭션이 쥔 채 일괄 승인하면 그 항목만
 * {@code LOCK_CONFLICT} 실패가 되고 응답은 200이며 앞 항목 커밋이 유지돼야 한다(수정 전: 예외가 항목 catch를 빠져나가 응답 전체 409).
 *
 * <p>대기 초과를 빨리 재현하려고 lock wait를 1초로 줄인다. 속성 집합은 {@code LockTimeoutErrorMappingIntegrationTest}와 같게 둬 컨텍스트를
 * 공유한다. 행 락 관찰을 위해 클래스에 {@code @Transactional}을 두지 않는다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.hikari.connection-init-sql=SET time_zone = '+09:00', SESSION innodb_lock_wait_timeout = 1",
        "zslab.order.auto-confirm.enabled=false",
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false"
})
class AdminClaimBulkApproveLockConflictIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/claims/bulk/approve";
    private static final long ADMIN = 96201L;
    private static final long BUYER = 96202L;
    private static final long SELLER = 96203L;
    private static final long PRODUCT_ID = 96204L;
    private static final long VARIANT_ID = 96205L;
    /** 케이스별 id = BASE + case*10 (주문 +0 · 품목 +1 · 클레임 +2). */
    private static final long BASE = 96200L;
    private static final int FIRST_CASE = 1;
    private static final int LOCKED_CASE = 2;
    private static final int LOCK_RACE_TIMEOUT_SECONDS = 30;
    private static final String LOCK_CONFLICT_MESSAGE = "다른 처리와 겹쳤습니다. 잠시 후 다시 시도해 주세요.";

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
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("PF-05 둘째 클레임 주문 행 락 보유 중 일괄 승인 → 200 · 첫 항목 성공·커밋 유지 · 둘째 LOCK_CONFLICT(고정 문구)·전이 없음")
    void lockedOrder_becomesItemFailure_andKeepsCommittedItem() throws Exception {
        String first = seedReturnCase(FIRST_CASE);
        String locked = seedReturnCase(LOCKED_CASE);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Void> holder = pool.submit(() -> {
                tx.executeWithoutResult(status -> {
                    jdbc.queryForList("SELECT id FROM `order` WHERE id = ? FOR UPDATE", orderId(LOCKED_CASE));
                    holding.countDown();
                    awaitQuietly(release);
                });
                return null;
            });
            assertThat(holding.await(LOCK_RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("락 보유 트랜잭션 진입").isTrue();

            mockMvc.perform(post(URL).with(authHeaders.admin(ADMIN)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"claimPublicIds\":[\"" + first + "\",\"" + locked + "\"]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.successCount").value(1))
                    .andExpect(jsonPath("$.failureCount").value(1))
                    .andExpect(jsonPath("$.results[0].success").value(true))
                    .andExpect(jsonPath("$.results[1].claimPublicId").value(locked))
                    .andExpect(jsonPath("$.results[1].success").value(false))
                    .andExpect(jsonPath("$.results[1].code").value("LOCK_CONFLICT"))
                    .andExpect(jsonPath("$.results[1].message").value(LOCK_CONFLICT_MESSAGE));

            release.countDown();
            holder.get(LOCK_RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(claimStatus(FIRST_CASE)).as("앞 항목 커밋 유지").isEqualTo("APPROVED");
        assertThat(claimStatus(LOCKED_CASE)).as("락 충돌 항목은 전이 없음").isEqualTo("REQUESTED");
    }

    // ===== 시드·정리(모든 SQL은 ? 바인딩 + 정적 문자열 — SQL injection 위험 없음) =====

    /** 배송 완료 주문·품목(수량 1)·반품 요청(단순 변심 → 승인 제안). 승인 커밋 후 처리는 알림뿐이다. */
    private String seedReturnCase(int caseNo) {
        long orderId = orderId(caseNo);
        String claimPid = pid("clm_", "PF05C" + caseNo);
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'DELIVERED', 10000, 0, 0, NOW(6), NOW(6))",
                        orderId, pid("ord_", "PF05O" + caseNo), BUYER, "ORDPF05" + orderId);
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                        + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, 10000, 10000, 'RETURN_REQUESTED', NOW(6), NOW(6), '테스트 상품', 1000)",
                        orderId + 1, pid("oit_", "PF05I" + caseNo), orderId, PRODUCT_ID, VARIANT_ID, SELLER);
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                        + "requested_by, requested_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'RETURN', 'BUYER_CHANGED_MIND', 'REQUESTED', 'DELIVERED', ?, NOW(6), NOW(6), NOW(6))",
                        orderId + 2, claimPid, orderId + 1, BUYER);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
        return claimPid;
    }

    private void cleanup() {
        long firstId = orderId(FIRST_CASE);
        long lastId = orderId(LOCKED_CASE) + 9;
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", BUYER);
                jdbc.update("DELETE FROM claim_suggestion_record WHERE claim_id BETWEEN ? AND ?", firstId, lastId);
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'CLAIM' AND target_id BETWEEN ? AND ?", firstId, lastId);
                jdbc.update("DELETE FROM notification_log WHERE target_type = 'CLAIM' AND target_id BETWEEN ? AND ?", firstId, lastId);
                jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", firstId, lastId);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", firstId, lastId);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", firstId, lastId);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static long orderId(int caseNo) {
        return BASE + caseNo * 10L;
    }

    private String claimStatus(int caseNo) {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, orderId(caseNo) + 2);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(LOCK_RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("락 보유 래치 대기 중 인터럽트", exception);
        }
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩)으로 30자 public_id를 만든다. */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
