package com.zslab.mall.grade.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.grade.service.GradeRecalculationBatchService;
import com.zslab.mall.support.AbstractIntegrationTest;
import com.zslab.mall.user.enums.GradeSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link GradeRecalculationScheduler} 통합 테스트(Track 96-6·실 MariaDB). 스케줄러 진입점 → {@code GradeRecalculationBatchService}
 * → 탈퇴 회원 제외·잠금 기간 내 수동 등급 유지·잠금 만료 시 AUTO 복귀를 실 커밋으로 검증한다.
 *
 * <p><b>스케줄러 자동 발화 차단</b>: {@code AbstractIntegrationTest}가 킬스위치를 전역으로 내리므로 빈이 없고(T1 검증 겸), 배치는
 * 스케줄러를 직접 생성해 호출한다. 시드는 AdminGradeControllerIntegrationTest 헬퍼 동형이며 탈퇴 판별을 위해 user 행을 함께 넣는다.
 */
class GradeRecalculationSchedulerIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_ACTIVE = 97701L;
    private static final long BUYER_WITHDRAWN = 97702L;
    private static final long BUYER_LOCKED = 97703L;
    private static final long BUYER_LOCK_EXPIRED = 97704L;
    private static final long ORDER_BASE = 97800L;
    private static final long PLATINUM_AMOUNT = 2_000_000L;

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private GradeRecalculationBatchService gradeRecalculationBatchService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private GradeRecalculationScheduler scheduler;
    private long silverId;
    private long platinumId;
    private int seq = 0;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        scheduler = new GradeRecalculationScheduler(gradeRecalculationBatchService);
        cleanup();
        silverId = gradeId("SILVER");
        platinumId = gradeId("PLATINUM");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 킬스위치 빈 없음 / 탈퇴 회원 제외: 활성 buyer는 PLATINUM·AUTO, 탈퇴 buyer는 SILVER·EVENT 유지")
    void killSwitch_andWithdrawnExcluded() {
        assertThat(applicationContext.getBeanNamesForType(GradeRecalculationScheduler.class)).isEmpty();
        seedBuyer(BUYER_ACTIVE, null, GradeSource.EVENT, null, PLATINUM_AMOUNT);
        seedBuyer(BUYER_WITHDRAWN, LocalDateTime.now().minusDays(1), GradeSource.EVENT, null, PLATINUM_AMOUNT);

        scheduler.recalculateBatch();

        assertGrade(BUYER_ACTIVE, platinumId, "AUTO");
        assertGrade(BUYER_WITHDRAWN, silverId, "EVENT");
    }

    @Test
    @DisplayName("T2 잠금 규칙: 잠금 기간 내 MANUAL 유지 / 잠금 만료 buyer는 AUTO 복귀")
    void lockRespected_andExpiredLockReturnsToAuto() {
        seedBuyer(BUYER_LOCKED, null, GradeSource.MANUAL, LocalDateTime.now().plusDays(30), PLATINUM_AMOUNT);
        seedBuyer(BUYER_LOCK_EXPIRED, null, GradeSource.MANUAL, LocalDateTime.now().minusDays(1), PLATINUM_AMOUNT);

        scheduler.recalculateBatch();

        assertGrade(BUYER_LOCKED, silverId, "MANUAL");
        assertGrade(BUYER_LOCK_EXPIRED, platinumId, "AUTO");
    }

    // ---------- seed·helpers (native·FK off·모든 값 바인딩·SQL injection 위험 없음) ----------

    private long gradeId(String code) {
        return jdbc.queryForObject("SELECT id FROM buyer_grade WHERE code = ?", Long.class, code);
    }

    /** user(탈퇴 시각 선택) + buyer_profile(SILVER 시작·source·잠금 선택) + CONFIRMED 품목 1건. */
    private void seedBuyer(long buyerId, LocalDateTime withdrawnAt, GradeSource source, LocalDateTime lockedUntil,
            long confirmedTotal) {
        long orderId = ORDER_BASE + (++seq);
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, withdrawn_at, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
                        buyerId, pid("usr_", "GRDS" + buyerId), withdrawnAt == null ? null : Timestamp.valueOf(withdrawnAt));
                jdbc.update("INSERT INTO buyer_profile "
                        + "(user_id, grade_id, grade_source, grade_locked_until, grade_updated_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, NOW(6), NOW(6))",
                        buyerId, silverId, source.name(), lockedUntil == null ? null : Timestamp.valueOf(lockedUntil));
                jdbc.update("INSERT INTO `order` "
                        + "(id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', 0, 0, 0, NOW(6), NOW(6))",
                        orderId, pid("ord_", "GRDS" + orderId), buyerId, "grds-" + orderId);
                jdbc.update("INSERT INTO order_item "
                        + "(public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                        + "item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, 1, 1, 1, 1, ?, ?, 'CONFIRMED', NOW(6), NOW(6), '테스트 상품', 1000)",
                        pid("oit_", "GRDS" + orderId), orderId, confirmedTotal, confirmedTotal);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void assertGrade(long buyerId, long expectedGradeId, String expectedSource) {
        Long gradeId = jdbc.queryForObject("SELECT grade_id FROM buyer_profile WHERE user_id = ?", Long.class, buyerId);
        String source = jdbc.queryForObject("SELECT grade_source FROM buyer_profile WHERE user_id = ?", String.class, buyerId);
        assertThat(gradeId).isEqualTo(expectedGradeId);
        assertThat(source).isEqualTo(expectedSource);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM order_item WHERE order_id BETWEEN 97800 AND 97899");
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN 97800 AND 97899");
                jdbc.update("DELETE FROM buyer_profile WHERE user_id BETWEEN 97700 AND 97799");
                jdbc.update("DELETE FROM `user` WHERE id BETWEEN 97700 AND 97799");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
