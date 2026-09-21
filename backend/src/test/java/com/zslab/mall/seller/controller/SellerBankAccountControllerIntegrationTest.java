package com.zslab.mall.seller.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.crypto.AesGcmTextEncryptor;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/**
 * 셀러 본인 정산계좌 통합 테스트(Track 90-D-3·D-199·실 MariaDB·HTTP 경유). {@code GET/POST /api/v1/seller/bank-accounts}.
 *
 * <p><b>커버</b>: OWNER 첫 등록 201(주 계좌·VERIFIED·감사 CREATE 마스킹·DB 암호문) · 두 번째 등록 비주계좌 · 비OWNER(STAFF) 403
 * SELLER_OWNER_REQUIRED(RED 선증명 대상) · SUSPENDED 403 · 무인증 401 · BUYER 403 · 검증 400 · GET 본인 셀러 계좌만(타 셀러 미포함·
 * 응답 키 화이트리스트·원 계좌번호 부재·STAFF도 조회 가능) · 셀러 첫 등록 후 관리자 pay 200·bank_account_id 스냅샷 일치.
 *
 * <p>시드 계좌는 {@link AesGcmTextEncryptor} 빈으로 암호화해 INSERT한다(Converter strict). 모든 시드 SQL은 ? 바인딩이다.
 */
@AutoConfigureMockMvc
class SellerBankAccountControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/seller/bank-accounts";
    private static final String PAY_URL = "/api/v1/admin/settlements/";
    private static final long OWNER_USER_ID = 9620L;
    private static final long STAFF_USER_ID = 9621L;
    private static final long BUYER_ID = 9622L;
    private static final long ADMIN_ID = 9623L;
    private static final long SELLER_ID = 9620L;
    private static final long OTHER_SELLER_ID = 9621L;
    private static final long OTHER_ACCOUNT_ID = 9621L;
    private static final long SETTLEMENT_ID = 9620L;
    private static final String NUMBER_FIRST = "110-123-456789";
    private static final String NUMBER_SECOND = "220-987-654321";
    private static final String OTHER_NUMBER = "330-555-777999";
    /** 응답 키 화이트리스트 — 전체 계좌번호 필드가 조용히 추가되는 것을 막는다. */
    private static final Set<String> RESPONSE_KEYS = Set.of(
            "id", "bankCode", "accountNumberSuffix", "accountHolder", "isPrimary", "status", "createdAt");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AesGcmTextEncryptor bankAccountEncryptor;

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
    @DisplayName("T1 OWNER 첫 등록 → 201·주 계좌·VERIFIED·응답 끝 4자리만·DB 암호문·감사 CREATE(accountNumber MASKED·suffix 기록)")
    void register_firstByOwner_created() throws Exception {
        seed(SellerStatus.ACTIVE);

        String body = mockMvc.perform(post(URL).headers(authHeaders.seller(OWNER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(json("KB", NUMBER_FIRST, "대표자")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bankCode").value("KB"))
                .andExpect(jsonPath("$.accountNumberSuffix").value("6789"))
                .andExpect(jsonPath("$.accountHolder").value("대표자"))
                .andExpect(jsonPath("$.isPrimary").value(true))
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(NUMBER_FIRST);
        assertThat(topLevelKeys(body)).containsExactlyInAnyOrderElementsOf(RESPONSE_KEYS);
        long accountId = objectMapper.readTree(body).get("id").asLong();
        String stored = jdbc.queryForObject("SELECT account_number FROM seller_bank_account WHERE id = ?", String.class, accountId);
        assertThat(stored).startsWith("v1:").doesNotContain(NUMBER_FIRST);
        assertThat(bankAccountEncryptor.decrypt(stored)).isEqualTo(NUMBER_FIRST);
        assertThat(jdbc.queryForObject("SELECT verified_at FROM seller_bank_account WHERE id = ?", Timestamp.class, accountId)).isNotNull();

        List<Map<String, Object>> audits = audits(accountId, OWNER_USER_ID);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("action")).isEqualTo("CREATE");
        assertThat(audits.get(0).get("actor_role")).isEqualTo("SELLER");
        JsonNode diff = objectMapper.readTree(audits.get(0).get("diff_json").toString());
        assertThat(diff.get("accountNumber").asText()).isEqualTo("***MASKED***");
        assertThat(diff.get("accountNumberSuffix").get("after").asText()).isEqualTo("6789");
        assertThat(diff.toString()).doesNotContain(NUMBER_FIRST);
    }

    @Test
    @DisplayName("T2 두 번째 등록 → 201·비주계좌(첫 계좌 주 계좌 유지)·GET 2건 등록순")
    void register_secondByOwner_notPrimary() throws Exception {
        seed(SellerStatus.ACTIVE);
        mockMvc.perform(post(URL).headers(authHeaders.seller(OWNER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(json("KB", NUMBER_FIRST, "대표자")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(URL).headers(authHeaders.seller(OWNER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(json("SHINHAN", NUMBER_SECOND, "대표자")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isPrimary").value(false))
                .andExpect(jsonPath("$.accountNumberSuffix").value("4321"));

        mockMvc.perform(get(URL).headers(authHeaders.seller(OWNER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].isPrimary").value(true))
                .andExpect(jsonPath("$[0].accountNumberSuffix").value("6789"))
                .andExpect(jsonPath("$[1].isPrimary").value(false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_bank_account WHERE seller_id = ? AND is_primary = 1",
                Integer.class, SELLER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("T3 STAFF(비OWNER) POST → 403 SELLER_OWNER_REQUIRED·행 0·감사 0 (RED 선증명 대상)")
    void register_byStaff_forbidden() throws Exception {
        seed(SellerStatus.ACTIVE);

        mockMvc.perform(post(URL).headers(authHeaders.seller(STAFF_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(json("KB", NUMBER_FIRST, "대표자")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_OWNER_REQUIRED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_bank_account WHERE seller_id = ?", Integer.class, SELLER_ID))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor_user_id = ?", Integer.class, STAFF_USER_ID))
                .isZero();
    }

    @Test
    @DisplayName("T4 SUSPENDED 셀러 OWNER POST → 403 SELLER_SUSPENDED·행 0 / GET은 200")
    void register_suspended_forbidden() throws Exception {
        seed(SellerStatus.SUSPENDED);

        mockMvc.perform(post(URL).headers(authHeaders.seller(OWNER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(json("KB", NUMBER_FIRST, "대표자")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_SUSPENDED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_bank_account WHERE seller_id = ?", Integer.class, SELLER_ID))
                .isZero();

        mockMvc.perform(get(URL).headers(authHeaders.seller(OWNER_USER_ID))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T5 무인증 → 401 · BUYER 토큰 → 403 (GET·POST 모두)")
    void wrongActor_rejected() throws Exception {
        seed(SellerStatus.ACTIVE);
        String content = json("KB", NUMBER_FIRST, "대표자");

        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(post(URL).headers(authHeaders.buyer(BUYER_ID)).contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_bank_account WHERE seller_id = ?", Integer.class, SELLER_ID))
                .isZero();
    }

    @Test
    @DisplayName("T6 검증 실패 → 400 VALIDATION_FAILED(계좌번호 문자 포함·bankCode 공백·예금주 51자)·행 0")
    void register_invalid_400() throws Exception {
        seed(SellerStatus.ACTIVE);

        mockMvc.perform(post(URL).headers(authHeaders.seller(OWNER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(json("KB", "110-ABC-456", "대표자")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(URL).headers(authHeaders.seller(OWNER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(json(" ", NUMBER_FIRST, "대표자")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL).headers(authHeaders.seller(OWNER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(json("KB", NUMBER_FIRST, "가".repeat(51))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_bank_account WHERE seller_id = ?", Integer.class, SELLER_ID))
                .isZero();
    }

    @Test
    @DisplayName("T7 GET → 본인 셀러 계좌만(타 셀러 계좌 미포함)·STAFF도 조회 가능·응답 본문에 원 계좌번호 부재·키 화이트리스트")
    void list_ownSellerOnly_masked() throws Exception {
        seed(SellerStatus.ACTIVE);
        mockMvc.perform(post(URL).headers(authHeaders.seller(OWNER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(json("KB", NUMBER_FIRST, "대표자")))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(get(URL).headers(authHeaders.seller(STAFF_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].accountNumberSuffix").value("6789"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(NUMBER_FIRST).doesNotContain(OTHER_NUMBER).doesNotContain("7999");
        assertThat(topLevelKeys(objectMapper.readTree(body).get(0))).containsExactlyInAnyOrderElementsOf(RESPONSE_KEYS);
    }

    @Test
    @DisplayName("T8 셀러 첫 등록 후 관리자 pay → 200·PAID·settlement.bank_account_id = 셀러가 등록한 계좌 id(스냅샷)")
    void register_thenAdminPay_snapshotsSellerAccount() throws Exception {
        seed(SellerStatus.ACTIVE);
        mockMvc.perform(post(PAY_URL + SETTLEMENT_ID + "/pay").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_BANK_ACCOUNT_MISSING"));

        String body = mockMvc.perform(post(URL).headers(authHeaders.seller(OWNER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(json("KB", NUMBER_FIRST, "대표자")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long accountId = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(post(PAY_URL + SETTLEMENT_ID + "/pay").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        assertThat(jdbc.queryForObject("SELECT bank_account_id FROM settlement WHERE id = ?", Long.class, SETTLEMENT_ID))
                .isEqualTo(accountId);
    }

    // ---------- seed·helpers ----------
    // 모든 시드 INSERT는 바인딩 파라미터 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seed(SellerStatus status) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        OWNER_USER_ID, pid("usr_", "BAOWN"));
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        STAFF_USER_ID, pid("usr_", "BASTF"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, updated_at) "
                                + "VALUES (?, ?, '계좌셀러', '대표', ?, 1000, NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "BASLR"), status.name());
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, updated_at) "
                                + "VALUES (?, ?, '타셀러', '대표', 'ACTIVE', 1000, NOW(6), NOW(6))",
                        OTHER_SELLER_ID, pid("slr_", "BAOTH"));
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'",
                        OWNER_USER_ID, SELLER_ID);
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_STAFF'",
                        STAFF_USER_ID, SELLER_ID);
                jdbc.update("INSERT INTO seller_bank_account (id, seller_id, bank_code, account_number, account_holder, is_primary, "
                                + "status, verified_at, created_at, updated_at) VALUES (?, ?, 'WOORI', ?, '타대표', 1, 'VERIFIED', NOW(6), NOW(6), NOW(6))",
                        OTHER_ACCOUNT_ID, OTHER_SELLER_ID, bankAccountEncryptor.encrypt(OTHER_NUMBER));
                jdbc.update("INSERT INTO settlement (id, seller_id, bank_account_id, period_start, period_end, gross_amount, fee_amount, "
                                + "commission_rate, refund_amount, net_amount, status, created_at, updated_at) "
                                + "VALUES (?, ?, NULL, '2026-08-01 00:00:00', '2026-08-31 23:59:59', 10000, 1000, 1000, 0, 9000, 'CONFIRMED', NOW(6), NOW(6))",
                        SETTLEMENT_ID, SELLER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE actor_user_id IN (?, ?, ?)", OWNER_USER_ID, STAFF_USER_ID, ADMIN_ID);
                jdbc.update("DELETE FROM settlement WHERE seller_id IN (?, ?)", SELLER_ID, OTHER_SELLER_ID);
                jdbc.update("DELETE FROM seller_bank_account WHERE seller_id IN (?, ?)", SELLER_ID, OTHER_SELLER_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?)", OWNER_USER_ID, STAFF_USER_ID);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SELLER_ID, OTHER_SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", OWNER_USER_ID, STAFF_USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private List<Map<String, Object>> audits(long accountId, long actorUserId) {
        return jdbc.queryForList("SELECT action, actor_role, diff_json FROM audit_log WHERE target_type = 'SETTLEMENT_BANK_ACCOUNT' "
                + "AND target_id = ? AND actor_user_id = ? ORDER BY id", accountId, actorUserId);
    }

    private String json(String bankCode, String accountNumber, String accountHolder) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of("bankCode", bankCode, "accountNumber", accountNumber, "accountHolder", accountHolder));
    }

    private Set<String> topLevelKeys(String body) throws Exception {
        return topLevelKeys(objectMapper.readTree(body));
    }

    private static Set<String> topLevelKeys(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
