package com.zslab.mall.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.user.enums.GradeSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Track 52 Phase 2 감사 적재 배선 E2E 통합 테스트(실 MariaDB). 3소비처(정산·상품승인·등급) admin endpoint를 HTTP로 구동해
 * 같은 트랜잭션 동기 감사 적재가 audit_log 행으로 남는지(actor·action·target_type·target_id·diff_json)와 등급 무변경 skip을
 * 검증한다(AdminGradeControllerIntegrationTest 시드/정리 패턴 준용).
 *
 * <p>클래스 {@code @Transactional} 없음(실 커밋 구동). 시드/정리는 {@link TransactionTemplate}+{@code FOREIGN_KEY_CHECKS=0}
 * (try-finally). audit_log는 actor_user_id={@link #ADMIN_ID} 기준으로 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditWiringIntegrationTest {

    private static final long ADMIN_ID = 97001L;

    // 정산
    private static final long SETTLEMENT_SELLER = 9722L; // Settlement 전이 테스트(9600~9610)와 겹치지 않는 범위
    // 상품
    private static final long PRODUCT_ID = 97310L;
    private static final long PRODUCT_SELLER = 97310L;
    private static final long PRODUCT_CATEGORY = 97310L;
    private static final String PRODUCT_PID = pid("prd_", "AUDITPRODUCT");
    // 등급
    private static final long BUYER_CHANGE = 97401L;   // SILVER→GOLD (감사 발생)
    private static final long ORDER_CHANGE = 97501L;
    private static final String USER_PID_CHANGE = pid("usr_", "AUDITGRDCHANGE");
    private static final long BUYER_SAME = 97402L;      // SILVER→SILVER (무변경·skip)
    private static final long ORDER_SAME = 97502L;
    private static final String USER_PID_SAME = pid("usr_", "AUDITGRDSAME");

    static final MariaDBContainer<?> MARIADB;

    static {
        MARIADB = new MariaDBContainer<>(DockerImageName.parse("mariadb:11.4"));
        MARIADB.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
        registry.add("spring.datasource.driver-class-name", MARIADB::getDriverClassName);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private long silverId;
    private long goldId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        silverId = gradeId("SILVER");
        goldId = gradeId("GOLD");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("정산 confirm: UPDATE/SETTLEMENT 감사 1건·diff status PENDING→CONFIRMED")
    void settlementConfirm_recordsAudit() throws Exception {
        long settlementId = seedSettlement("PENDING");

        mockMvc.perform(post("/api/v1/admin/settlements/" + settlementId + "/confirm")
                        .headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk());

        Map<String, Object> row = singleAuditRow("SETTLEMENT", settlementId);
        assertThat(row.get("action")).isEqualTo("UPDATE");
        assertThat(row.get("actor_user_id")).isEqualTo(ADMIN_ID);
        assertThat(row.get("actor_role")).isEqualTo("ADMIN");
        assertThat((String) row.get("diff_json")).contains("status").contains("PENDING").contains("CONFIRMED");
    }

    @Test
    @DisplayName("상품 approve: APPROVE/PRODUCT 감사 1건·diff status PENDING→SALE")
    void productApprove_recordsAudit() throws Exception {
        seedProduct("PENDING");

        mockMvc.perform(post("/api/v1/admin/products/" + PRODUCT_PID + "/approve")
                        .headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk());

        Map<String, Object> row = singleAuditRow("PRODUCT", PRODUCT_ID);
        assertThat(row.get("action")).isEqualTo("APPROVE");
        assertThat((String) row.get("diff_json")).contains("PENDING").contains("SALE");
    }

    @Test
    @DisplayName("등급 재산정(변경): UPDATE/USER 감사 1건·diff gradeId(SILVER→GOLD)")
    void gradeRecalculate_changed_recordsAudit() throws Exception {
        seedUser(BUYER_CHANGE, USER_PID_CHANGE);
        seedBuyerWithConfirmed(BUYER_CHANGE, ORDER_CHANGE, silverId, 500_000L); // → GOLD

        mockMvc.perform(post("/api/v1/admin/buyers/" + USER_PID_CHANGE + "/grade/recalculate")
                        .headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNoContent());

        Map<String, Object> row = singleAuditRow("USER", BUYER_CHANGE);
        assertThat(row.get("action")).isEqualTo("UPDATE");
        assertThat((String) row.get("diff_json"))
                .contains("gradeId")
                .contains(String.valueOf(silverId))
                .contains(String.valueOf(goldId));
    }

    @Test
    @DisplayName("등급 재산정(무변경): 동일 등급이면 diff 빈 맵 → 감사 적재 skip(0건)")
    void gradeRecalculate_unchanged_skipsAudit() throws Exception {
        seedUser(BUYER_SAME, USER_PID_SAME);
        seedBuyerWithConfirmed(BUYER_SAME, ORDER_SAME, silverId, 100_000L); // 여전히 SILVER

        mockMvc.perform(post("/api/v1/admin/buyers/" + USER_PID_SAME + "/grade/recalculate")
                        .headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNoContent());

        assertThat(auditCount("USER", BUYER_SAME)).isZero();
    }

    // ---------- 조회 helper ----------

    private Map<String, Object> singleAuditRow(String targetType, long targetId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action, actor_user_id, actor_role, diff_json FROM audit_log "
                        + "WHERE target_type = ? AND target_id = ? AND actor_user_id = ?",
                targetType, targetId, ADMIN_ID);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private int auditCount(String targetType, long targetId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE target_type = ? AND target_id = ? AND actor_user_id = ?",
                Integer.class, targetType, targetId, ADMIN_ID);
        return count == null ? 0 : count;
    }

    // ---------- seed helpers(바인딩 파라미터·정적 SQL·SQL injection 위험 없음) ----------

    private long gradeId(String code) {
        return jdbc.queryForObject("SELECT id FROM buyer_grade WHERE code = ?", Long.class, code);
    }

    private long seedSettlement(String settlementStatus) {
        return tx.execute(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, "
                                + "created_at, updated_at) VALUES (?, ?, '감사셀러', '대표', 'ACTIVE', 1000, NOW(6), NOW(6))",
                        SETTLEMENT_SELLER, pid("slr_", "AUDITSETTLE"));
                jdbc.update("INSERT INTO seller_bank_account (id, seller_id, bank_code, account_number, account_holder, "
                                + "is_primary, status, created_at, updated_at) "
                                + "VALUES (?, ?, '004', '123', '대표', 1, 'VERIFIED', NOW(6), NOW(6))",
                        SETTLEMENT_SELLER, SETTLEMENT_SELLER);
                jdbc.update("INSERT INTO settlement (seller_id, bank_account_id, period_start, period_end, "
                                + "gross_amount, fee_amount, commission_rate, refund_amount, net_amount, status, "
                                + "created_at, updated_at) "
                                + "VALUES (?, ?, NOW(6), NOW(6), 10000, 1000, 1000, 0, 9000, ?, NOW(6), NOW(6))",
                        SETTLEMENT_SELLER, SETTLEMENT_SELLER, settlementStatus);
                return jdbc.queryForObject("SELECT id FROM settlement WHERE seller_id = ?", Long.class, SETTLEMENT_SELLER);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedProduct(String productStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '감사카테고리', 0, 0, NOW(6), NOW(6))", PRODUCT_CATEGORY);
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, "
                                + "created_at, updated_at) VALUES (?, ?, '감사상품셀러', '대표', 'ACTIVE', 1000, NOW(6), NOW(6))",
                        PRODUCT_SELLER, pid("slr_", "AUDITPRODSELLER"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '감사상품', ?, 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, PRODUCT_PID, PRODUCT_SELLER, PRODUCT_CATEGORY, productStatus);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedUser(long userId, String publicId) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        userId, publicId);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedBuyerWithConfirmed(long buyerId, long orderId, long initialGradeId, long confirmedTotal) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO buyer_profile "
                        + "(user_id, grade_id, grade_source, grade_updated_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, NOW(6), NOW(6))", buyerId, initialGradeId, GradeSource.EVENT.name());
                jdbc.update("INSERT INTO `order` "
                        + "(id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', 0, 0, 0, NOW(6), NOW(6))",
                        orderId, pid("ord_", "AUDO" + orderId), buyerId, "ordno-" + orderId);
                jdbc.update("INSERT INTO order_item "
                        + "(public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                        + "item_status, created_at, updated_at) "
                        + "VALUES (?, ?, 1, 1, 1, 1, ?, ?, 'CONFIRMED', NOW(6), NOW(6))",
                        pid("oit_", "AUDI" + orderId), orderId, confirmedTotal, confirmedTotal);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE actor_user_id = ?", ADMIN_ID);
                jdbc.update("DELETE FROM settlement WHERE seller_id = ?", SETTLEMENT_SELLER);
                jdbc.update("DELETE FROM seller_bank_account WHERE seller_id = ?", SETTLEMENT_SELLER);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SETTLEMENT_SELLER, PRODUCT_SELLER);
                jdbc.update("DELETE FROM category WHERE id = ?", PRODUCT_CATEGORY);
                jdbc.update("DELETE FROM order_item WHERE order_id IN (?, ?)", ORDER_CHANGE, ORDER_SAME);
                jdbc.update("DELETE FROM `order` WHERE id IN (?, ?)", ORDER_CHANGE, ORDER_SAME);
                jdbc.update("DELETE FROM buyer_profile WHERE user_id IN (?, ?)", BUYER_CHANGE, BUYER_SAME);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", BUYER_CHANGE, BUYER_SAME);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
