package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimApproved;
import com.zslab.mall.claim.event.ClaimInspectionPassed;
import com.zslab.mall.claim.event.ClaimPickedUp;
import com.zslab.mall.claim.event.ClaimRejected;
import com.zslab.mall.order.enums.OrderItemStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import com.zslab.mall.common.security.AuthHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * 셀러 클레임 처리 endpoint 제거 반전 통합 테스트(Track 92·D-196). "셀러는 조회만·처리는 관리자"가 확정이므로
 * {@code POST /api/v1/claims/{id}/approve · /reject · /confirm-pickup · /inspect}는 셀러 토큰으로 403(SecurityConfig BUYER
 * 광범위 규칙)이어야 하며, 인가·endpoint가 되살아나면 200/422로 RED가 난다(STEP 701에서 매처 복원으로 RED 재현 확인).
 *
 * <p><b>단언 경계</b>: 403·클레임 상태 유지·milestone 컬럼 NULL 유지·이벤트 0건까지만 본다. 관리자 처리 정상 경로는
 * AdminClaimIntegrationTest·ClaimReturnIntegrationTest 책임이다. 404 단언은 채택하지 않는다(경로 부재 NoResourceFoundException이
 * GlobalExceptionHandler catch-all에 걸려 500이 될 수 있음).
 *
 * <p><b>트랜잭션·시드(β)</b>: 단일 트랜잭션(@Transactional)으로 종료 시 롤백한다. order_item은 product/variant/seller
 * FK 상위 그래프를 요구하므로(V1__init.sql) {@code SET FOREIGN_KEY_CHECKS=0}으로 order·order_item·claim만 시딩한다
 * (ClaimIntegrationTest 패턴 1:1·seedOrderItem만 sellerId 파라미터화). {@code =0}은 try-finally로 {@code =1} 복원과
 * 짝을 이룬다(LT-02·HikariCP 커넥션 풀 오염 차단).
 */
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
class SellerClaimIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER = 9501L;
    private static final long SELLER_A = 9001L; // 품목 소유 셀러
    // Track 36 γ Phase 3: actorId(JWT subject)를 seller_id와 다른 값으로 둔다 — user.id==seller.id 우연일치 은폐 제거.
    private static final long SELLER_A_USER = 9051L; // SELLER_A 소속 user(actorId)

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthHeaders authHeaders;

    @Autowired
    private ApplicationEvents events;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void seedSellerUsers() {
        // 소유 셀러의 실 매핑(seller_user·ACTIVE seller)을 시드해 403이 "소유 셀러라도 차단"임을 보장한다(행 부재 401과 구분).
        seed(() -> {
            seedSeller(SELLER_A, pid("slr_", "SCISLA"));
            seedSellerUser(SELLER_A_USER, SELLER_A);
        });
    }

    // ===== R1~R4: 셀러 처리 endpoint 제거 반전(Track 92) — 인가가 되살아나면 200으로 RED =====

    @Test
    @DisplayName("R1 승인: 소유 셀러 토큰 → 403 FORBIDDEN·REQUESTED 유지·ClaimApproved 0건(Track 92 셀러 처리 endpoint 제거)")
    void approve_ownerSellerToken_returns403_noTransition() throws Exception {
        long orderId = 9631L;
        long orderItemId = 9632L;
        long claimId = 9633L;
        String claimPid = pid("clm_", "R1");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "R1ORD"), BUYER);
            seedOrderItem(orderItemId, pid("oit_", "R1OIT"), orderId, SELLER_A, OrderItemStatus.PAID);
            seedClaim(claimId, claimPid, orderItemId, ClaimType.CANCEL, ClaimStatus.REQUESTED, BUYER, "셀러 승인 차단");
        });

        mockMvc.perform(post("/api/v1/claims/" + claimPid + "/approve")
                        .headers(authHeaders.seller(SELLER_A_USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(claimStatus(claimId)).isEqualTo("REQUESTED");
        assertThat(events.stream(ClaimApproved.class).count()).isZero();
    }

    @Test
    @DisplayName("R2 거부: 소유 셀러 토큰 → 403 FORBIDDEN·REQUESTED 유지·ClaimRejected 0건(Track 92)")
    void reject_ownerSellerToken_returns403_noTransition() throws Exception {
        long orderId = 9641L;
        long orderItemId = 9642L;
        long claimId = 9643L;
        String claimPid = pid("clm_", "R2");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "R2ORD"), BUYER);
            seedOrderItem(orderItemId, pid("oit_", "R2OIT"), orderId, SELLER_A, OrderItemStatus.PAID);
            seedClaim(claimId, claimPid, orderItemId, ClaimType.CANCEL, ClaimStatus.REQUESTED, BUYER, "셀러 거부 차단");
        });

        mockMvc.perform(post("/api/v1/claims/" + claimPid + "/reject")
                        .headers(authHeaders.seller(SELLER_A_USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"OUT_OF_POLICY\",\"memo\":\"차단 확인\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(claimStatus(claimId)).isEqualTo("REQUESTED");
        assertThat(events.stream(ClaimRejected.class).count()).isZero();
    }

    @Test
    @DisplayName("R3 회수 확인: 소유 셀러 토큰 → 403 FORBIDDEN·picked_up_at NULL 유지·ClaimPickedUp 0건(Track 92)")
    void confirmPickup_ownerSellerToken_returns403_noTransition() throws Exception {
        long orderId = 9651L;
        long orderItemId = 9652L;
        long claimId = 9653L;
        String claimPid = pid("clm_", "R3");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "R3ORD"), BUYER);
            seedOrderItem(orderItemId, pid("oit_", "R3OIT"), orderId, SELLER_A, OrderItemStatus.RETURN_REQUESTED);
            seedClaim(claimId, claimPid, orderItemId, ClaimType.RETURN, ClaimStatus.APPROVED, BUYER, "셀러 회수 확인 차단");
        });

        mockMvc.perform(post("/api/v1/claims/" + claimPid + "/confirm-pickup")
                        .headers(authHeaders.seller(SELLER_A_USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(claimStatus(claimId)).isEqualTo("APPROVED");
        assertThat(claimColumn(claimId, "picked_up_at")).isNull();
        assertThat(events.stream(ClaimPickedUp.class).count()).isZero();
    }

    @Test
    @DisplayName("R4 검수: 소유 셀러 토큰 → 403 FORBIDDEN·inspected_at NULL 유지·ClaimInspectionPassed 0건(Track 92)")
    void inspect_ownerSellerToken_returns403_noTransition() throws Exception {
        long orderId = 9661L;
        long orderItemId = 9662L;
        long claimId = 9663L;
        String claimPid = pid("clm_", "R4");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "R4ORD"), BUYER);
            seedOrderItem(orderItemId, pid("oit_", "R4OIT"), orderId, SELLER_A, OrderItemStatus.RETURN_REQUESTED);
            seedClaim(claimId, claimPid, orderItemId, ClaimType.RETURN, ClaimStatus.APPROVED, BUYER, "셀러 검수 차단");
        });

        mockMvc.perform(post("/api/v1/claims/" + claimPid + "/inspect")
                        .headers(authHeaders.seller(SELLER_A_USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"PASS\",\"restock\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(claimStatus(claimId)).isEqualTo("APPROVED");
        assertThat(claimColumn(claimId, "inspected_at")).isNull();
        assertThat(events.stream(ClaimInspectionPassed.class).count()).isZero();
    }

    // ===== 시드·검증 헬퍼(ClaimIntegrationTest 패턴 1:1·seedOrderItem만 sellerId 파라미터화) =====

    /**
     * FK 비활성 상태로 시드 작업을 수행하고 {@code SET FOREIGN_KEY_CHECKS=1}로 복원한다(LT-02 try-finally).
     * 복원 누락 시 HikariCP 커넥션 풀에 FK 비활성이 잔류해 후속 테스트를 오염시킨다.
     */
    private void seed(Runnable seedingWork) {
        try {
            execute("SET FOREIGN_KEY_CHECKS = 0");
            seedingWork.run();
        } finally {
            execute("SET FOREIGN_KEY_CHECKS = 1");
        }
        entityManager.flush();
        entityManager.clear();
    }

    // 모든 시드 INSERT는 ?n positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).
    private void seedOrder(long id, String publicId, long buyerId) {
        entityManager.createNativeQuery(
                        "INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?1, ?2, ?3, ?4, 'PAID', 10000, 0, 0, NOW(6), NOW(6))")
                .setParameter(1, id)
                .setParameter(2, publicId)
                .setParameter(3, buyerId)
                .setParameter(4, "ORDIT" + id)
                .executeUpdate();
    }

    private void seedOrderItem(long id, String publicId, long orderId, long sellerId, OrderItemStatus itemStatus) {
        entityManager.createNativeQuery(
                        "INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                                + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                                + "VALUES (?1, ?2, ?3, 1, 1, ?4, 1, 10000, 10000, ?5, NOW(6), NOW(6), '테스트 상품', 1000)")
                .setParameter(1, id)
                .setParameter(2, publicId)
                .setParameter(3, orderId)
                .setParameter(4, sellerId)
                .setParameter(5, itemStatus.name())
                .executeUpdate();
    }

    private void seedClaim(long id, String publicId, long orderItemId, ClaimType type, ClaimStatus status,
            long requestedBy, String reasonDetail) {
        entityManager.createNativeQuery(
                        "INSERT INTO claim (id, public_id, order_item_id, type, reason_code, reason_detail, status, "
                                + "previous_order_item_status, requested_by, requested_at, created_at, updated_at) "
                                + "VALUES (?1, ?2, ?3, ?4, 'BUYER_CHANGED_MIND', ?5, ?6, 'PAID', ?7, NOW(6), NOW(6), NOW(6))")
                .setParameter(1, id)
                .setParameter(2, publicId)
                .setParameter(3, orderItemId)
                .setParameter(4, type.name())
                .setParameter(5, reasonDetail)
                .setParameter(6, status.name())
                .setParameter(7, requestedBy)
                .executeUpdate();
    }

    private void seedSeller(long sellerId, String publicId) {
        entityManager.createNativeQuery(
                        "INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?1, ?2, '클레임셀러', '대표', 'ACTIVE', NOW(6), NOW(6))")
                .setParameter(1, sellerId)
                .setParameter(2, publicId)
                .executeUpdate();
    }

    private void seedSellerUser(long userId, long sellerId) {
        entityManager.createNativeQuery(
                        "INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                                + "SELECT ?1, ?2, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'")
                .setParameter(1, userId)
                .setParameter(2, sellerId)
                .executeUpdate();
    }

    private void execute(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
    }

    /** claim 단일 컬럼 조회(picked_up_at·inspected_at NULL 유지 확인용). 컬럼명은 테스트 상수 리터럴만 전달한다(SQL injection 위험 없음). */
    private Object claimColumn(long claimId, String column) {
        return entityManager.createNativeQuery("SELECT " + column + " FROM claim WHERE id = ?1")
                .setParameter(1, claimId).getSingleResult();
    }

    private String claimStatus(long claimId) {
        return (String) entityManager.createNativeQuery("SELECT status FROM claim WHERE id = ?1")
                .setParameter(1, claimId).getSingleResult();
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
