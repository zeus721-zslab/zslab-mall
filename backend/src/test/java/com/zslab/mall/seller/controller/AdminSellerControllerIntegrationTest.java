package com.zslab.mall.seller.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * 판매자 provisioning endpoint E2E 통합 테스트(Track 37·실 MariaDB). HTTP → {@code AdminSellerController} →
 * {@code SellerProvisioningService} → seller/seller_user DB 흐름을 실 커밋·HTTP 경유로 실측한다(라이브 트랩 차단·
 * {@link com.zslab.mall.inventory.controller.AdminInventoryControllerIntegrationTest} 패턴 정합).
 *
 * <p><b>중복 소속 가드 = 옵션 A(DB 제약 기반·D-121 연장)</b>: 이미 소속된 user로 요청하면 seller INSERT 후 seller_user
 * saveAndFlush가 uk_seller_user_user_id(V12)를 위반해 409로 변환되고 seller INSERT까지 원자 롤백된다. ④는 409 상태·code를,
 * ⑥은 동일 시나리오에서 seller 미잔존(count 0)을 검증해 {@code @Transactional} 원자성을 실증한다({@code @Transactional}
 * 제거 시 ⑥이 실패해야 진짜·우연일치 은폐 금지).
 *
 * <p><b>트랜잭션</b>: provisioning의 @Transactional 커밋을 JdbcTemplate 직접 조회로 검증하므로 클래스에 {@code @Transactional}을
 * 두지 않는다. 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)로 한다.
 */
@AutoConfigureMockMvc
class AdminSellerControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/sellers";

    private static final long ADMIN_ID = 8801L;        // ADMIN 액터(JWT subject·hasRole ADMIN 통과)
    private static final long NON_ADMIN_USER = 8802L;   // BUYER 토큰 subject(인가 거부 403)
    private static final long OWNER_USER_ID = 8810L;    // 성공 케이스 owner(미소속 user)
    private static final long BOUND_USER_ID = 8811L;    // 기존 seller에 소속된 user(V12·롤백)
    private static final long MISSING_USER_ID = 8899L;  // 미시드 user(404)
    private static final long EXISTING_SELLER_ID = 8820L; // BOUND_USER가 소속된 기존 seller

    private static final String NEW_COMPANY = "트랙37신규셀러";      // ① 생성 대상
    private static final String FORBIDDEN_COMPANY = "트랙37금지셀러";  // ② 403·미생성
    private static final String DUP_COMPANY = "트랙37중복셀러";      // ④ 409·롤백
    private static final String SUSPENDED_COMPANY = "트랙37정지셀러"; // ⑤ 400·미생성
    private static final String ROLLBACK_COMPANY = "트랙37롤백셀러";  // ⑥ 롤백 검증 대상
    private static final String EXISTING_COMPANY = "트랙37기존셀러";  // 기존 seller seed

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
    @DisplayName("① ADMIN 성공: 유효 provisioning → 201·sellerPublicId 반환·seller 1건·owner seller_user 1건(role SELLER_OWNER)")
    void provision_validAdmin_returns201_persistsSellerAndOwnerMapping() throws Exception {
        seed(() -> seedUser(OWNER_USER_ID, "T37OWN"));

        mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(provisionBody(NEW_COMPANY, OWNER_USER_ID, "ACTIVE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sellerPublicId").exists());

        // seller 입점 1건 + owner seller_user(role SELLER_OWNER) 1건이 실제 커밋됐는지 확인(role 배선 핵심 검증)
        assertThat(sellerCountByCompany(NEW_COMPANY)).isEqualTo(1);
        assertThat(ownerMappingCount(NEW_COMPANY, OWNER_USER_ID)).isEqualTo(1);

        // 감사 배선(Track 55·D-139): 입점 성공 시 CREATE/SELLER 감사 1건·target_id=seller.id·diff 최소셋(sellerPublicId·status·ownerUserId).
        long sellerId = sellerIdByCompany(NEW_COMPANY);
        Map<String, Object> audit = singleAuditRow("SELLER", sellerId, ADMIN_ID);
        assertThat(audit.get("action")).isEqualTo("CREATE");
        assertThat(audit.get("actor_role")).isEqualTo("ADMIN");
        assertThat((String) audit.get("diff_json"))
                .contains("sellerPublicId")
                .contains("status").contains("ACTIVE")
                .contains("ownerUserId").contains(String.valueOf(OWNER_USER_ID));
    }

    @Test
    @DisplayName("② 비ADMIN: BUYER 토큰 → 403 FORBIDDEN·seller 미생성(SecurityConfig hasRole 인가 강제)")
    void provision_nonAdmin_returns403() throws Exception {
        // SecurityConfig /api/v1/admin/** → hasRole(ADMIN)이 컨트롤러 이전에 인가 거부한다(owner 시드 불요).
        mockMvc.perform(post(URL)
                        .headers(authHeaders.buyer(NON_ADMIN_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(provisionBody(FORBIDDEN_COMPANY, OWNER_USER_ID, "ACTIVE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(sellerCountByCompany(FORBIDDEN_COMPANY)).isZero();
    }

    @Test
    @DisplayName("③ owner 미존재: 미시드 ownerUserId → 404 USER_NOT_FOUND·seller 미생성(seller INSERT 이전 단락)")
    void provision_unknownOwner_returns404() throws Exception {
        mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(provisionBody(NEW_COMPANY, MISSING_USER_ID, "ACTIVE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        assertThat(sellerCountByCompany(NEW_COMPANY)).isZero();
    }

    @Test
    @DisplayName("④ 중복 소속(V12): 이미 다른 seller에 소속된 userId → 409 SELLER_USER_ALREADY_EXISTS")
    void provision_userAlreadyBound_returns409() throws Exception {
        seed(() -> {
            seedUser(BOUND_USER_ID, "T37BND");
            seedSeller(EXISTING_SELLER_ID, EXISTING_COMPANY, "T37EXS");
            seedSellerUser(BOUND_USER_ID, EXISTING_SELLER_ID);
        });

        mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(provisionBody(DUP_COMPANY, BOUND_USER_ID, "ACTIVE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELLER_USER_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("⑤ status 제한: SUSPENDED 초기 상태 요청 → 400 MALFORMED_REQUEST·seller 미생성(Lifecycle invariant)")
    void provision_suspendedInitialStatus_returns400() throws Exception {
        // owner는 존재(existsById 통과)하나 SUSPENDED는 생성 불가 초기 상태 → validateInitialStatus가 seller save 이전에 400.
        seed(() -> seedUser(OWNER_USER_ID, "T37OWN"));

        mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(provisionBody(SUSPENDED_COMPANY, OWNER_USER_ID, "SUSPENDED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertThat(sellerCountByCompany(SUSPENDED_COMPANY)).isZero();
    }

    @Test
    @DisplayName("⑥ 원자 롤백: seller_user INSERT 실패(V12) 시 앞선 seller INSERT 미잔존(count 0)·@Transactional 원자성 실증")
    void provision_sellerUserInsertFails_rollsBackSeller() throws Exception {
        seed(() -> {
            seedUser(BOUND_USER_ID, "T37BND");
            seedSeller(EXISTING_SELLER_ID, EXISTING_COMPANY, "T37EXS");
            seedSellerUser(BOUND_USER_ID, EXISTING_SELLER_ID);
        });

        // 옵션 A: seller save(INSERT) 성공 후 seller_user saveAndFlush가 uk_seller_user_user_id(V12) 위반 → 409 + 전체 롤백.
        mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(provisionBody(ROLLBACK_COMPANY, BOUND_USER_ID, "ACTIVE")))
                .andExpect(status().isConflict());

        // 핵심: seller INSERT가 롤백돼 ROLLBACK_COMPANY seller가 잔존하지 않는다(@Transactional 제거 시 이 단언이 실패 = 진짜 원자성 테스트).
        assertThat(sellerCountByCompany(ROLLBACK_COMPANY)).isZero();
    }

    // ---------- seed·helpers (AdminInventoryControllerIntegrationTest 패턴·? positional 바인딩·SQL injection 없음) ----------

    private void seed(Runnable seedingWork) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedingWork.run();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedUser(long id, String tag) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                id, pid("usr_", tag));
    }

    private void seedSeller(long id, String companyName, String tag) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, '대표', 'ACTIVE', NOW(6), NOW(6))",
                id, pid("slr_", tag), companyName);
    }

    // role_id는 SELLER_OWNER seed(V11)에서 조회해 seed-id 하드코딩을 피한다.
    private void seedSellerUser(long userId, long sellerId) {
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                        + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'",
                userId, sellerId);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                // 감사 배선(Track 55) 잔존 방지: 입점 성공 시 actor=ADMIN_ID 기준 audit_log가 실 커밋되므로 함께 정리(테스트 오염 차단).
                jdbc.update("DELETE FROM audit_log WHERE actor_user_id = ?", ADMIN_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?, ?)",
                        OWNER_USER_ID, BOUND_USER_ID, NON_ADMIN_USER);
                jdbc.update("DELETE FROM seller WHERE company_name IN (?, ?, ?, ?, ?, ?)",
                        NEW_COMPANY, FORBIDDEN_COMPANY, DUP_COMPANY, SUSPENDED_COMPANY, ROLLBACK_COMPANY, EXISTING_COMPANY);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", OWNER_USER_ID, BOUND_USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String provisionBody(String companyName, long ownerUserId, String status) {
        return "{"
                + "\"companyName\":\"" + companyName + "\","
                + "\"businessNo\":null,"
                + "\"ceoName\":\"대표\","
                + "\"contactEmail\":null,"
                + "\"contactPhone\":null,"
                + "\"status\":\"" + status + "\","
                + "\"ownerUserId\":" + ownerUserId
                + "}";
    }

    private int sellerCountByCompany(String companyName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seller WHERE company_name = ?", Integer.class, companyName);
        return count == null ? 0 : count;
    }

    private int ownerMappingCount(String companyName, long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seller_user su "
                        + "JOIN seller s ON su.seller_id = s.id "
                        + "JOIN role r ON su.role_id = r.id "
                        + "WHERE s.company_name = ? AND su.user_id = ? AND r.code = 'SELLER_OWNER'",
                Integer.class, companyName, userId);
        return count == null ? 0 : count;
    }

    private long sellerIdByCompany(String companyName) {
        return jdbc.queryForObject("SELECT id FROM seller WHERE company_name = ?", Long.class, companyName);
    }

    // audit_log 단건 조회(AuditWiringIntegrationTest 패턴·? 바인딩·SQL injection 없음). 정확히 1건 검증.
    private Map<String, Object> singleAuditRow(String targetType, long targetId, long actorUserId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action, actor_user_id, actor_role, diff_json FROM audit_log "
                        + "WHERE target_type = ? AND target_id = ? AND actor_user_id = ?",
                targetType, targetId, actorUserId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
