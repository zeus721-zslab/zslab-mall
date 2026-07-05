package com.zslab.mall.grade.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.user.enums.GradeSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
 * Admin 등급 재산정 endpoint E2E 통합 테스트(Track 51 Phase 3·실 MariaDB). HTTP → {@code AdminGradeController} →
 * 배치/단일 GradeService → DB 실 커밋을 검증한다(AdminSettlementControllerIntegrationTest 패턴 1:1).
 *
 * <p><b>커버</b>: ① 일괄 배치(다등급 반영·요약 카운트) ② 부분 성공(lock buyer skip·나머지 반영·미중단) ③ 단일 α(publicId 해소)
 * ④ 권한(비ADMIN 403). buyer_grade·grade_policy는 V15 Flyway 시드를 쓰고, order·order_item·buyer_profile·user만 native 시드한다.
 *
 * <p><b>트랜잭션</b>: 실 커밋으로 산정을 구동하므로 클래스 {@code @Transactional} 없음. 시드/정리는 {@link TransactionTemplate}
 * + {@code FOREIGN_KEY_CHECKS=0}(try-finally). buyer 생애 누적 SUM은 시각 필터가 없어 JdbcTemplate 시드로 충분하다(P2 TZ 트랩 무관).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminGradeControllerIntegrationTest {

    private static final long ADMIN_ID = 90001L;   // JWT 액터(DB 행 불요)
    private static final long BUYER_TOKEN_ID = 90002L;
    private static final String BATCH_URL = "/api/v1/admin/grades/recalculate";
    private static final String SINGLE_USER_PUBLIC_ID = pid("usr_", "GRDONE");

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
    private long platinumId;
    private int seq = 0;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        silverId = gradeId("SILVER");
        goldId = gradeId("GOLD");
        platinumId = gradeId("PLATINUM");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("① 일괄 배치: 다등급 buyer 반영·요약 카운트(total/success/failure)")
    void recalculateAll_appliesGradesAndSummarizes() throws Exception {
        seedBuyerWithConfirmed(91001L, 92001L, silverId, GradeSource.EVENT, null, 100_000L);   // → SILVER
        seedBuyerWithConfirmed(91002L, 92002L, silverId, GradeSource.EVENT, null, 500_000L);   // → GOLD
        seedBuyerWithConfirmed(91003L, 92003L, silverId, GradeSource.EVENT, null, 2_000_000L); // → PLATINUM

        mockMvc.perform(post(BATCH_URL).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.success").value(3))
                .andExpect(jsonPath("$.failure").value(0));

        assertGrade(91001L, silverId, "AUTO");
        assertGrade(91002L, goldId, "AUTO");
        assertGrade(91003L, platinumId, "AUTO");
    }

    @Test
    @DisplayName("② 부분 성공: lock buyer는 skip(무변경)·나머지 반영·배치 미중단")
    void recalculateAll_skipsLockedAndContinues() throws Exception {
        // lock buyer: PLATINUM 구간 lifetime이어도 산정 개입 금지(SILVER·MANUAL 유지)
        seedBuyerWithConfirmed(93001L, 94001L, silverId, GradeSource.MANUAL,
                LocalDateTime.now().plusDays(30), 2_000_000L);
        seedBuyerWithConfirmed(93002L, 94002L, silverId, GradeSource.EVENT, null, 500_000L); // → GOLD

        mockMvc.perform(post(BATCH_URL).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.success").value(2)) // lock skip도 성공 집계(세분화 미도입)
                .andExpect(jsonPath("$.failure").value(0));

        assertGrade(93001L, silverId, "MANUAL"); // lock → 무변경
        assertGrade(93002L, goldId, "AUTO");
    }

    @Test
    @DisplayName("③ 단일 α: publicId 해소 후 해당 buyer만 재산정 → 204")
    void recalculateOne_byPublicId_returns204() throws Exception {
        seedUser(95001L, SINGLE_USER_PUBLIC_ID);
        seedBuyerWithConfirmed(95001L, 96001L, silverId, GradeSource.EVENT, null, 1_000_000L); // → PLATINUM

        mockMvc.perform(post("/api/v1/admin/buyers/" + SINGLE_USER_PUBLIC_ID + "/grade/recalculate")
                        .headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNoContent());

        assertGrade(95001L, platinumId, "AUTO");
    }

    @Test
    @DisplayName("④ 권한: 비ADMIN(BUYER) 토큰 → 배치·단일 모두 403")
    void nonAdmin_returns403() throws Exception {
        mockMvc.perform(post(BATCH_URL).headers(authHeaders.buyer(BUYER_TOKEN_ID)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/buyers/" + SINGLE_USER_PUBLIC_ID + "/grade/recalculate")
                        .headers(authHeaders.buyer(BUYER_TOKEN_ID)))
                .andExpect(status().isForbidden());
    }

    // ---------- seed·helpers (native·FK off·모든 값 바인딩·SQL injection 위험 없음) ----------

    private long gradeId(String code) {
        return jdbc.queryForObject("SELECT id FROM buyer_grade WHERE code = ?", Long.class, code);
    }

    private void seedBuyerWithConfirmed(long buyerId, long orderId, long initialGradeId,
            GradeSource source, LocalDateTime lockedUntil, long confirmedTotal) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                if (lockedUntil == null) {
                    jdbc.update("INSERT INTO buyer_profile "
                            + "(user_id, grade_id, grade_source, grade_updated_at, created_at, updated_at) "
                            + "VALUES (?, ?, ?, NULL, NOW(6), NOW(6))", buyerId, initialGradeId, source.name());
                } else {
                    jdbc.update("INSERT INTO buyer_profile "
                            + "(user_id, grade_id, grade_source, grade_locked_until, grade_updated_at, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, NULL, NOW(6), NOW(6))",
                            buyerId, initialGradeId, source.name(), Timestamp.valueOf(lockedUntil));
                }
                jdbc.update("INSERT INTO `order` "
                        + "(id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', 0, 0, 0, NOW(6), NOW(6))",
                        orderId, pid("ord_", "GRDO" + orderId), buyerId, "ordno-" + orderId);
                jdbc.update("INSERT INTO order_item "
                        + "(public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                        + "item_status, created_at, updated_at) "
                        + "VALUES (?, ?, 1, 1, 1, 1, ?, ?, 'CONFIRMED', NOW(6), NOW(6))",
                        pid("oit_", "GRDI" + (++seq)), orderId, confirmedTotal, confirmedTotal);
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

    private void assertGrade(long buyerId, long expectedGradeId, String expectedSource) {
        Long gradeId = jdbc.queryForObject(
                "SELECT grade_id FROM buyer_profile WHERE user_id = ?", Long.class, buyerId);
        String source = jdbc.queryForObject(
                "SELECT grade_source FROM buyer_profile WHERE user_id = ?", String.class, buyerId);
        assertThat(gradeId).isEqualTo(expectedGradeId);
        assertThat(source).isEqualTo(expectedSource);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM order_item WHERE order_id BETWEEN 92000 AND 96999");
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN 92000 AND 96999");
                jdbc.update("DELETE FROM buyer_profile WHERE user_id BETWEEN 91000 AND 96999");
                jdbc.update("DELETE FROM `user` WHERE id BETWEEN 95000 AND 96999");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
