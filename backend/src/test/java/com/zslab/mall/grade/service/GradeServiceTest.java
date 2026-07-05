package com.zslab.mall.grade.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.common.config.AuditingConfig;
import com.zslab.mall.grade.entity.BuyerGrade;
import com.zslab.mall.grade.enums.BuyerGradeCode;
import com.zslab.mall.grade.repository.BuyerGradeRepository;
import com.zslab.mall.user.entity.BuyerProfile;
import com.zslab.mall.user.enums.GradeSource;
import com.zslab.mall.user.repository.BuyerProfileRepository;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * {@link GradeService#recalculate} 통합 테스트(MariaDB Testcontainers·실제 산정 동작). V15 시드 정책(SILVER [0,300000)·
 * GOLD [300000,1000000)·PLATINUM [1000000,MAX])에 대해 반개구간 경계·CONFIRMED disjoint·lock 가드·결과 반영을 실증한다.
 *
 * <p>상위 그래프(user·order·product 등)는 본 트랙 소관 밖이라 {@code FOREIGN_KEY_CHECKS=0} 하에 order·order_item·
 * buyer_profile만 native 시드한다(SellerGrossAggregationTest 패턴 준용). buyer_grade·grade_policy는 V15 Flyway 시드를 사용한다.
 */
@Import({AuditingConfig.class, GradeService.class})
class GradeServiceTest extends Batch1DataJpaTestBase {

    @Autowired
    private GradeService gradeService;

    @Autowired
    private BuyerProfileRepository buyerProfileRepository;

    @Autowired
    private BuyerGradeRepository buyerGradeRepository;

    private final Map<BuyerGradeCode, Long> gradeIds = new EnumMap<>(BuyerGradeCode.class);
    private int seq = 0;

    @BeforeEach
    void resolveSeededGradeIds() {
        for (BuyerGrade grade : buyerGradeRepository.findAll()) {
            gradeIds.put(grade.getCode(), grade.getId());
        }
    }

    @AfterEach
    void restoreForeignKeyChecks() {
        // SET FOREIGN_KEY_CHECKS는 세션 변수라 커넥션 풀로 누수된다(공유 풀). 미복구 시 FK 위반 기대 테스트 오염 → 반드시 복구.
        entityManager.getEntityManager().createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
    }

    // ---- 시드 헬퍼(native·FK off) ----

    private void disableFkChecks() {
        entityManager.getEntityManager().createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
    }

    private void seedBuyerProfile(long userId, long gradeId, GradeSource source, LocalDateTime lockedUntil) {
        disableFkChecks();
        // lockedUntil이 null이면 컬럼 자체를 생략(DEFAULT NULL) — native query의 null 파라미터 타입 미결정 트랩 회피.
        // 컬럼 목록만 분기하며 모든 값은 :바인딩(SQL injection 위험 없음).
        boolean hasLock = lockedUntil != null;
        Query query = entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO buyer_profile "
                + "(user_id, grade_id, grade_source, " + (hasLock ? "grade_locked_until, " : "")
                + "grade_updated_at, created_at, updated_at) "
                + "VALUES (:userId, :gradeId, :source, " + (hasLock ? ":lockedUntil, " : "")
                + "NULL, NOW(6), NOW(6))");
        query.setParameter("userId", userId);
        query.setParameter("gradeId", gradeId);
        query.setParameter("source", source.name());
        if (hasLock) {
            query.setParameter("lockedUntil", lockedUntil);
        }
        query.executeUpdate();
    }

    private void seedOrder(long orderId, long buyerId) {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO `order` "
                + "(id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, created_at, updated_at) "
                + "VALUES (:id, :pid, :buyerId, :orderNo, 'CONFIRMED', 0, 0, 0, NOW(6), NOW(6))");
        query.setParameter("id", orderId);
        query.setParameter("pid", String.format("ord_%026d", orderId));
        query.setParameter("buyerId", buyerId);
        query.setParameter("orderNo", "ordno-" + orderId);
        query.executeUpdate();
    }

    // confirmed_at은 buyer 생애 누적 SUM 쿼리가 참조하지 않으므로(기간 필터 없음) NULL로 둔다.
    private void addOrderItem(long orderId, String itemStatus, long totalPrice) {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO order_item "
                + "(public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                + "item_status, created_at, updated_at) "
                + "VALUES (:pid, :orderId, 1, 1, 1, 1, :total, :total, :status, NOW(6), NOW(6))");
        query.setParameter("pid", String.format("oit_%026d", ++seq));
        query.setParameter("orderId", orderId);
        query.setParameter("total", totalPrice);
        query.setParameter("status", itemStatus);
        query.executeUpdate();
    }

    private BuyerProfile recalculateAndReload(long buyerId) {
        entityManager.flush();
        entityManager.clear();
        gradeService.recalculate(buyerId);
        entityManager.flush();
        entityManager.clear();
        return buyerProfileRepository.findById(buyerId).orElseThrow();
    }

    // ---- 테스트 ----

    @Test
    @DisplayName("반개구간 경계: 299999→SILVER·300000→GOLD·999999→GOLD·1000000→PLATINUM")
    void recalculate_halfOpenBoundaries() {
        assertConfirmedLifetimeMapsTo(81001L, 82001L, 299_999L, BuyerGradeCode.SILVER);
        assertConfirmedLifetimeMapsTo(81002L, 82002L, 300_000L, BuyerGradeCode.GOLD);
        assertConfirmedLifetimeMapsTo(81003L, 82003L, 999_999L, BuyerGradeCode.GOLD);
        assertConfirmedLifetimeMapsTo(81004L, 82004L, 1_000_000L, BuyerGradeCode.PLATINUM);
    }

    private void assertConfirmedLifetimeMapsTo(long buyerId, long orderId, long confirmedTotal, BuyerGradeCode expected) {
        seedBuyerProfile(buyerId, gradeIds.get(BuyerGradeCode.SILVER), GradeSource.EVENT, null);
        seedOrder(orderId, buyerId);
        addOrderItem(orderId, "CONFIRMED", confirmedTotal);

        BuyerProfile profile = recalculateAndReload(buyerId);

        assertThat(profile.getGradeId())
                .as("lifetime=%d 는 %s 구간", confirmedTotal, expected)
                .isEqualTo(gradeIds.get(expected));
        assertThat(profile.getGradeSource()).isEqualTo(GradeSource.AUTO);
        assertThat(profile.getGradeUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("disjoint: CANCELLED·RETURNED·EXCHANGED는 누적액에서 제외(CONFIRMED만 합산)")
    void recalculate_excludesNonConfirmedItems() {
        long buyerId = 83001L;
        long orderId = 84001L;
        seedBuyerProfile(buyerId, gradeIds.get(BuyerGradeCode.SILVER), GradeSource.AUTO, null);
        seedOrder(orderId, buyerId);
        addOrderItem(orderId, "CONFIRMED", 300_000L); // 유일 합산 대상 → GOLD 하한
        addOrderItem(orderId, "CANCELLED", 5_000_000L);
        addOrderItem(orderId, "RETURNED", 5_000_000L);
        addOrderItem(orderId, "EXCHANGED", 5_000_000L);

        BuyerProfile profile = recalculateAndReload(buyerId);

        // 환불군 15.3M이 포함됐다면 PLATINUM. GOLD면 CONFIRMED 300k만 합산됐음을 실증.
        assertThat(profile.getGradeId()).isEqualTo(gradeIds.get(BuyerGradeCode.GOLD));
        assertThat(profile.getGradeSource()).isEqualTo(GradeSource.AUTO);
    }

    @Test
    @DisplayName("lock 가드: grade_locked_until > now 이면 산정 skip(등급·source·updated_at 미변경)")
    void recalculate_skipsWhenLocked() {
        long buyerId = 85001L;
        long orderId = 86001L;
        long silverId = gradeIds.get(BuyerGradeCode.SILVER);
        // lock 미해제 상태에서 PLATINUM 구간 lifetime을 심어도 산정이 개입하면 안 됨
        seedBuyerProfile(buyerId, silverId, GradeSource.MANUAL, LocalDateTime.now().plusDays(30));
        seedOrder(orderId, buyerId);
        addOrderItem(orderId, "CONFIRMED", 1_000_000L);

        BuyerProfile profile = recalculateAndReload(buyerId);

        assertThat(profile.getGradeId()).isEqualTo(silverId);
        assertThat(profile.getGradeSource()).isEqualTo(GradeSource.MANUAL);
        assertThat(profile.getGradeUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("결과 반영: lock 없으면 source 무관 재산정 후 AUTO·updated_at 세팅(EVENT/PLATINUM→AUTO/SILVER 강등)")
    void recalculate_appliesAndFlipsSource() {
        long buyerId = 87001L;
        long orderId = 88001L;
        // 초기 PLATINUM·EVENT·lock 없음. CONFIRMED 0 → lifetime 0 → SILVER 강등·source AUTO 전환
        seedBuyerProfile(buyerId, gradeIds.get(BuyerGradeCode.PLATINUM), GradeSource.EVENT, null);
        seedOrder(orderId, buyerId);
        addOrderItem(orderId, "CANCELLED", 9_999_999L); // 비CONFIRMED → 제외 → lifetime 0

        BuyerProfile profile = recalculateAndReload(buyerId);

        assertThat(profile.getGradeId()).isEqualTo(gradeIds.get(BuyerGradeCode.SILVER));
        assertThat(profile.getGradeSource()).isEqualTo(GradeSource.AUTO);
        assertThat(profile.getGradeUpdatedAt()).isNotNull();
    }
}
