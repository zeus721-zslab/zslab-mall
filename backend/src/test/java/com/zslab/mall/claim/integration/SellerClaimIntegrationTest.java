package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimApproved;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * Seller Claim endpoint E2E 통합 테스트(β 패턴·D-92·STEP 2 β 결정·WARN-5). 실 MariaDB·Flyway·MockMvc로
 * HTTP → {@code SellerClaimController} → {@code ClaimService} → DB 흐름의 권한 검증·도메인 박제·이벤트 발행을 검증한다.
 *
 * <p><b>책임 경계(WARN-5)</b>: endpoint 권한(cross-tenant 404)·상태 박제(APPROVED/REJECTED)·이벤트 발행
 * (@RecordApplicationEvents)까지만 보장한다. ClaimApproved/Rejected 소비·AFTER_COMMIT 핸들러의 OrderItem 전이는
 * ClaimEventIntegrationTest 책임이며 본 테스트는 검증하지 않는다(@Transactional 롤백이라 AFTER_COMMIT 미발화).
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
    private static final long SELLER_B = 9002L; // 타 셀러(cross-tenant)
    // Track 36 γ Phase 3: actorId(JWT subject)를 seller_id와 다른 값으로 둔다 — user.id==seller.id 우연일치 은폐 제거.
    private static final long SELLER_A_USER = 9051L; // SELLER_A 소속 user(actorId)
    private static final long SELLER_B_USER = 9052L; // SELLER_B 소속 user(cross-tenant actorId)

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
        // resolver가 user.id→seller.id를 seller_user로 해소하므로 실 매핑을 시드한다(모든 테스트 공통·I4 미시드 케이스 포함).
        seed(() -> {
            seedSellerUser(SELLER_A_USER, SELLER_A);
            seedSellerUser(SELLER_B_USER, SELLER_B);
        });
    }

    // ===== I1·I2: 승인·거부 성공 =====

    @Test
    @DisplayName("I1 승인: 소유 셀러 → 200·APPROVED 박제·ClaimApproved 1회 발행")
    void approve_ownerSeller_returns200_publishesApproved() throws Exception {
        long orderId = 9601L;
        long orderItemId = 9602L;
        long claimId = 9603L;
        String claimPid = pid("clm_", "I1");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "I1ORD"), BUYER);
            seedOrderItem(orderItemId, pid("oit_", "I1OIT"), orderId, SELLER_A, OrderItemStatus.PAID);
            seedClaim(claimId, claimPid, orderItemId, ClaimType.CANCEL, ClaimStatus.REQUESTED, BUYER, "승인 대상");
        });

        mockMvc.perform(post("/api/v1/claims/" + claimPid + "/approve")
                        .headers(authHeaders.seller(SELLER_A_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(claimPid))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(claimStatus(claimId)).isEqualTo("APPROVED");
        assertThat(events.stream(ClaimApproved.class).count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("I2 거부: 소유 셀러 → 200·REJECTED 박제·ClaimRejected 1회 발행")
    void reject_ownerSeller_returns200_publishesRejected() throws Exception {
        long orderId = 9611L;
        long orderItemId = 9612L;
        long claimId = 9613L;
        String claimPid = pid("clm_", "I2");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "I2ORD"), BUYER);
            seedOrderItem(orderItemId, pid("oit_", "I2OIT"), orderId, SELLER_A, OrderItemStatus.PAID);
            seedClaim(claimId, claimPid, orderItemId, ClaimType.CANCEL, ClaimStatus.REQUESTED, BUYER, "거부 대상");
        });

        mockMvc.perform(post("/api/v1/claims/" + claimPid + "/reject")
                        .headers(authHeaders.seller(SELLER_A_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(claimStatus(claimId)).isEqualTo("REJECTED");
        assertThat(events.stream(ClaimRejected.class).count()).isEqualTo(1L);
    }

    // ===== I3·I4: 권한·미존재 =====

    @Test
    @DisplayName("I3 승인: 타 셀러 cross-tenant → 404·전이 없음·이벤트 0건(정보 노출 회피·D-92 Q3)")
    void approve_crossTenant_returns404_noEvent() throws Exception {
        long orderId = 9621L;
        long orderItemId = 9622L;
        long claimId = 9623L;
        String claimPid = pid("clm_", "I3");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "I3ORD"), BUYER);
            seedOrderItem(orderItemId, pid("oit_", "I3OIT"), orderId, SELLER_A, OrderItemStatus.PAID);
            seedClaim(claimId, claimPid, orderItemId, ClaimType.CANCEL, ClaimStatus.REQUESTED, BUYER, "타 셀러 접근");
        });

        mockMvc.perform(post("/api/v1/claims/" + claimPid + "/approve")
                        .headers(authHeaders.seller(SELLER_B_USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));

        assertThat(claimStatus(claimId)).isEqualTo("REQUESTED");
        assertThat(events.stream(ClaimApproved.class).count()).isZero();
        assertThat(events.stream(ClaimRejected.class).count()).isZero();
    }

    @Test
    @DisplayName("I4 승인: 미존재 claimPublicId → 404·이벤트 0건")
    void approve_unknownPublicId_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/claims/" + pid("clm_", "I4NONE") + "/approve")
                        .headers(authHeaders.seller(SELLER_A_USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));

        assertThat(events.stream(ClaimApproved.class).count()).isZero();
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
                                + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                                + "VALUES (?1, ?2, ?3, 1, 1, ?4, 1, 10000, 10000, ?5, NOW(6), NOW(6))")
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

    private String claimStatus(long claimId) {
        return (String) entityManager.createNativeQuery("SELECT status FROM claim WHERE id = ?1")
                .setParameter(1, claimId).getSingleResult();
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
