package com.zslab.mall.seller.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.crypto.AesGcmTextEncryptor;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 셀러 정산계좌 통합 테스트(Track 89-F·D-188·실 MariaDB·HTTP 경유·실 커밋). 등록(첫 계좌 자동 주 계좌·VERIFIED·감사 CREATE 마스킹)·
 * 수정(정산 참조 행 409·미참조 in-place·사유·무변경 no-op)·주 계좌 전환(demote→promote·UNIQUE·422)·응답 마스킹(끝 4자리만)·상세 계좌 목록·
 * 암호화 회귀(DB 컬럼 v1:·정산 지급 경로·정산 상세 스냅샷 끝 4자리)·권한을 검증한다.
 *
 * <p>시드 계좌는 {@link AesGcmTextEncryptor} 빈으로 암호화해 INSERT한다(Converter strict — 평문 시드는 조회 시 예외). 계좌 실값이 아닌
 * 테스트 상수만 쓴다. 트랜잭션: 명령 커밋을 JdbcTemplate로 검증하므로 클래스에 {@code @Transactional}을 두지 않는다. 모든 SQL은 ? 바인딩.
 */
@AutoConfigureMockMvc
class AdminSellerBankAccountControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String SELLER_URL = "/api/v1/admin/sellers";
    private static final long ADMIN_ID = 8890L;
    private static final long BUYER_ID = 8891L;
    private static final long S_EMPTY = 8801L;   // 계좌 0건
    private static final long S_PAID = 8802L;    // 주 계좌(PAID 정산 참조) + 비주계좌
    private static final long S_OTHER = 8803L;   // 타 셀러(404 은닉)
    private static final long ACCOUNT_PAID_PRIMARY = 8861L;
    private static final long ACCOUNT_PAID_SECOND = 8862L;
    private static final long ACCOUNT_OTHER = 8863L;
    private static final long SETTLEMENT_PAID = 8851L;
    private static final long SETTLEMENT_CONFIRMED = 8852L;
    private static final String NUMBER_PRIMARY = "110-000-001234";
    private static final String NUMBER_SECOND = "220-000-005678";
    private static final String NUMBER_OTHER = "330-000-009999";
    private static final String NUMBER_NEW = "440-000-004321";
    private static final String NUMBER_NEW2 = "550-000-008765";

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
        seed();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ==================== T1 권한 ====================

    @Test
    @DisplayName("T1 권한: 등록·수정·전환 — 무인증 401 / BUYER 403 / ADMIN 201·204")
    void authorization() throws Exception {
        String body = registerBody("KB", NUMBER_NEW, "홍길동");
        mockMvc.perform(post(accountsUrl(S_EMPTY)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(accountsUrl(S_EMPTY)).headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(accountUrl(S_PAID, ACCOUNT_PAID_SECOND)).headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody("KB", NUMBER_NEW, "홍길동", "사유")))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch(primaryUrl(S_PAID, ACCOUNT_PAID_SECOND)).headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(reasonBody("사유")))
                .andExpect(status().isForbidden());
        assertThat(count("SELECT COUNT(*) FROM seller_bank_account WHERE seller_id = ?", S_EMPTY)).isZero();

        mockMvc.perform(post(accountsUrl(S_EMPTY)).headers(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(patch(primaryUrl(S_PAID, ACCOUNT_PAID_SECOND)).headers(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(reasonBody("사유")))
                .andExpect(status().isNoContent());
    }

    // ==================== T2 등록 ====================

    @Test
    @DisplayName("T2 등록: 첫 계좌 자동 주 계좌·VERIFIED·verifiedAt / 두 번째는 비주계좌 / 응답 끝 4자리만 / DB 컬럼 v1: 암호문 / 감사 CREATE(계좌번호 마스킹) / 미존재 404 / 검증 400")
    void register() throws Exception {
        JsonNode first = readJson(mockMvc.perform(post(accountsUrl(S_EMPTY)).headers(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("KB", NUMBER_NEW, "홍길동")))
                .andExpect(status().isCreated()));
        assertThat(first.get("isPrimary").asBoolean()).isTrue();
        assertThat(first.get("status").asText()).isEqualTo("VERIFIED");
        assertThat(first.get("verifiedAt").asText()).isNotBlank();
        assertThat(first.get("accountNumberSuffix").asText()).isEqualTo("4321");
        assertThat(first.get("referencedBySettlement").asBoolean()).as("신규 행은 참조 정산 없음").isFalse();
        assertThat(first.get("bankCode").asText()).isEqualTo("KB");
        assertThat(first.get("accountHolder").asText()).isEqualTo("홍길동");
        assertThat(first.has("accountNumber")).isFalse();
        assertThat(first.toString()).doesNotContain(NUMBER_NEW);
        long firstId = first.get("id").asLong();

        JsonNode second = readJson(mockMvc.perform(post(accountsUrl(S_EMPTY)).headers(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("SHINHAN", NUMBER_NEW2, "홍길동")))
                .andExpect(status().isCreated()));
        assertThat(second.get("isPrimary").asBoolean()).isFalse();
        assertThat(second.get("accountNumberSuffix").asText()).isEqualTo("8765");

        // DB: 컬럼은 v1: 암호문·평문 미잔존·복호 시 원문 · 주 계좌 1건
        String stored = jdbc.queryForObject("SELECT account_number FROM seller_bank_account WHERE id = ?", String.class, firstId);
        assertThat(AesGcmTextEncryptor.isEncrypted(stored)).isTrue();
        assertThat(stored).doesNotContain(NUMBER_NEW);
        assertThat(bankAccountEncryptor.decrypt(stored)).isEqualTo(NUMBER_NEW);
        assertThat(count("SELECT COUNT(*) FROM seller_bank_account WHERE seller_id = ? AND is_primary = 1", S_EMPTY)).isEqualTo(1);

        // 감사: CREATE·SETTLEMENT_BANK_ACCOUNT·accountNumber 마스킹·suffix 병기
        List<Map<String, Object>> audits = audits(firstId);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("action")).isEqualTo("CREATE");
        String diff = (String) audits.get(0).get("diff_json");
        assertThat(diff).contains("***MASKED***").contains("\"4321\"").doesNotContain(NUMBER_NEW);
        // 외부 검토 지적 2 고정: diff_json의 accountNumber 키는 값 전체가 MASKED(전체 번호·암호문 모두 미기록)
        JsonNode diffJson = objectMapper.readTree(diff);
        assertThat(diffJson.get("accountNumber").asText()).isEqualTo("***MASKED***");
        assertThat(diffJson.get("accountNumberSuffix").get("after").asText()).isEqualTo("4321");

        // 상세: 목록 2건·주 계좌 = 첫 계좌·경고 해제
        JsonNode detail = readJson(mockMvc.perform(get(SELLER_URL + "/" + pid(S_EMPTY)).headers(admin())).andExpect(status().isOk()));
        assertThat(detail.get("bankAccounts")).hasSize(2);
        assertThat(detail.get("bankAccounts").get(0).get("id").asLong()).isEqualTo(firstId);
        assertThat(detail.get("primaryBankAccount").get("id").asLong()).isEqualTo(firstId);
        assertThat(detail.get("warnings").get("primaryBankAccountMissing").asBoolean()).isFalse();
        assertThat(detail.toString()).doesNotContain(NUMBER_NEW).doesNotContain(NUMBER_NEW2);

        // 미존재 셀러 404 · 검증 400(문자 포함·공백·길이)
        mockMvc.perform(post(accountsUrl(9999L)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("KB", NUMBER_NEW, "홍길동")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SELLER_NOT_FOUND"));
        mockMvc.perform(post(accountsUrl(S_EMPTY)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("KB", "12AB-3456", "홍길동")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(accountsUrl(S_EMPTY)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("", NUMBER_NEW, "홍길동")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(accountsUrl(S_EMPTY)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("KB", "1".repeat(31), "홍길동")))
                .andExpect(status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM seller_bank_account WHERE seller_id = ?", S_EMPTY)).isEqualTo(2);
    }

    // ==================== T3 수정 ====================

    @Test
    @DisplayName("T3 수정: 상세 referencedBySettlement 미리보기 = 실제(참조 행 true·409 / 미참조 false·204)·복호 일치·감사 UPDATE+reason / 사유 공백 400 / 무변경 감사 0 / 타 셀러 계좌 404")
    void update() throws Exception {
        // 미리보기(외부 검토 Q6): 상세 bankAccounts의 참조 플래그가 아래 실제 결과(409/204)와 일치해야 한다
        JsonNode before = readJson(mockMvc.perform(get(SELLER_URL + "/" + pid(S_PAID)).headers(admin())).andExpect(status().isOk()));
        assertThat(before.get("bankAccounts").get(0).get("id").asLong()).isEqualTo(ACCOUNT_PAID_PRIMARY);
        assertThat(before.get("bankAccounts").get(0).get("referencedBySettlement").asBoolean()).isTrue();
        assertThat(before.get("bankAccounts").get(1).get("id").asLong()).isEqualTo(ACCOUNT_PAID_SECOND);
        assertThat(before.get("bankAccounts").get(1).get("referencedBySettlement").asBoolean()).isFalse();

        // 참조 행(PAID 정산 bank_account_id) → 409·값 불변
        mockMvc.perform(put(accountUrl(S_PAID, ACCOUNT_PAID_PRIMARY)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("KB", NUMBER_NEW, "홍길동", "정정")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER_BANK_ACCOUNT_REFERENCED"));
        assertThat(decryptedNumber(ACCOUNT_PAID_PRIMARY)).isEqualTo(NUMBER_PRIMARY);
        assertThat(audits(ACCOUNT_PAID_PRIMARY)).isEmpty();

        // 미참조 행 → 204·in-place·verifiedAt 갱신·감사 UPDATE(reason·마스킹)
        mockMvc.perform(put(accountUrl(S_PAID, ACCOUNT_PAID_SECOND)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("WOORI", NUMBER_NEW, "김수정", "계좌 오기 정정")))
                .andExpect(status().isNoContent());
        assertThat(decryptedNumber(ACCOUNT_PAID_SECOND)).isEqualTo(NUMBER_NEW);
        assertThat(jdbc.queryForObject("SELECT bank_code FROM seller_bank_account WHERE id = ?", String.class, ACCOUNT_PAID_SECOND))
                .isEqualTo("WOORI");
        assertThat(jdbc.queryForObject("SELECT account_holder FROM seller_bank_account WHERE id = ?", String.class, ACCOUNT_PAID_SECOND))
                .isEqualTo("김수정");
        List<Map<String, Object>> audits = audits(ACCOUNT_PAID_SECOND);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("action")).isEqualTo("UPDATE");
        String diff = (String) audits.get(0).get("diff_json");
        assertThat(diff).contains("계좌 오기 정정").contains("***MASKED***").contains("\"5678\"").contains("\"4321\"")
                .doesNotContain(NUMBER_SECOND).doesNotContain(NUMBER_NEW);
        assertThat(objectMapper.readTree(diff).get("accountNumber").asText()).as("수정 diff도 before/after 모두 MASKED").isEqualTo("***MASKED***");

        // 무변경 → 204·감사 추가 없음
        mockMvc.perform(put(accountUrl(S_PAID, ACCOUNT_PAID_SECOND)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("WOORI", NUMBER_NEW, "김수정", "다시")))
                .andExpect(status().isNoContent());
        assertThat(audits(ACCOUNT_PAID_SECOND)).hasSize(1);

        // 사유 공백 400 · 타 셀러 계좌 404(존재 은닉) · 미존재 계좌 404
        mockMvc.perform(put(accountUrl(S_PAID, ACCOUNT_PAID_SECOND)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("KB", NUMBER_NEW2, "김수정", " ")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(put(accountUrl(S_PAID, ACCOUNT_OTHER)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("KB", NUMBER_NEW2, "김수정", "사유")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SELLER_BANK_ACCOUNT_NOT_FOUND"));
        mockMvc.perform(put(accountUrl(S_PAID, 99999L)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("KB", NUMBER_NEW2, "김수정", "사유")))
                .andExpect(status().isNotFound());
        assertThat(decryptedNumber(ACCOUNT_OTHER)).isEqualTo(NUMBER_OTHER);
    }

    // ==================== T4 주 계좌 전환 ====================

    @Test
    @DisplayName("T4 주 계좌 전환: demote→promote 204·기존 주 계좌 해제·주 계좌 1건 유지·감사·PENDING 정산 지급이 새 주 계좌로 / 이미 주 계좌 422 / 사유 공백 400 / 타 셀러 404")
    void changePrimary() throws Exception {
        mockMvc.perform(patch(primaryUrl(S_PAID, ACCOUNT_PAID_PRIMARY)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(reasonBody("사유")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SELLER_BANK_ACCOUNT_INVALID_STATE"));
        mockMvc.perform(patch(primaryUrl(S_PAID, ACCOUNT_PAID_SECOND)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(reasonBody("")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(primaryUrl(S_PAID, ACCOUNT_OTHER)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(reasonBody("사유")))
                .andExpect(status().isNotFound());
        assertThat(isPrimary(ACCOUNT_PAID_PRIMARY)).isTrue();

        mockMvc.perform(patch(primaryUrl(S_PAID, ACCOUNT_PAID_SECOND)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(reasonBody("계좌 변경 요청")))
                .andExpect(status().isNoContent());
        assertThat(isPrimary(ACCOUNT_PAID_PRIMARY)).isFalse();
        assertThat(isPrimary(ACCOUNT_PAID_SECOND)).isTrue();
        assertThat(count("SELECT COUNT(*) FROM seller_bank_account WHERE seller_id = ? AND is_primary = 1", S_PAID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT updated_at > created_at FROM seller_bank_account WHERE id = ?", Boolean.class,
                ACCOUNT_PAID_PRIMARY)).as("벌크 demote도 updated_at 갱신").isTrue();
        List<Map<String, Object>> audits = audits(ACCOUNT_PAID_SECOND);
        assertThat(audits).hasSize(1);
        assertThat((String) audits.get(0).get("diff_json")).contains("계좌 변경 요청").contains("previousPrimaryBankAccountIds")
                .contains(String.valueOf(ACCOUNT_PAID_PRIMARY)).doesNotContain(NUMBER_SECOND);

        // 상세: primaryBankAccount = 새 주 계좌·목록 2건 isPrimary 반영
        JsonNode detail = readJson(mockMvc.perform(get(SELLER_URL + "/" + pid(S_PAID)).headers(admin())).andExpect(status().isOk()));
        assertThat(detail.get("primaryBankAccount").get("id").asLong()).isEqualTo(ACCOUNT_PAID_SECOND);
        assertThat(detail.get("primaryBankAccount").get("accountNumberSuffix").asText()).isEqualTo("5678");
        assertThat(detail.get("bankAccounts")).hasSize(2);
        assertThat(detail.get("bankAccounts").get(0).get("isPrimary").asBoolean()).isFalse();
        assertThat(detail.get("bankAccounts").get(1).get("isPrimary").asBoolean()).isTrue();

        // 되돌리기(전환 재확인) → 다시 첫 계좌가 주 계좌
        mockMvc.perform(patch(primaryUrl(S_PAID, ACCOUNT_PAID_PRIMARY)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(reasonBody("원복")))
                .andExpect(status().isNoContent());
        assertThat(isPrimary(ACCOUNT_PAID_PRIMARY)).isTrue();
        assertThat(isPrimary(ACCOUNT_PAID_SECOND)).isFalse();
    }

    // ==================== T5 회귀: 암호화 후 정산 경로·상세 마스킹 ====================

    @Test
    @DisplayName("T5 회귀: 암호화된 계좌로 CONFIRMED 정산 지급(D-179 주 계좌 필수) 200·bank_account_id 스냅샷 / 정산 상세 스냅샷 끝 4자리 / 셀러 상세 끝 4자리 / 응답 어디에도 전체 계좌번호 없음")
    void regression_settlementPayAndMasking() throws Exception {
        mockMvc.perform(post("/api/v1/admin/settlements/" + SETTLEMENT_CONFIRMED + "/pay").headers(admin()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT bank_account_id FROM settlement WHERE id = ?", Long.class, SETTLEMENT_CONFIRMED))
                .isEqualTo(ACCOUNT_PAID_PRIMARY);

        String settlementDetail = mockMvc.perform(get("/api/v1/admin/settlements/" + SETTLEMENT_PAID).headers(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccount.accountNumberSuffix").value("1234"))
                .andExpect(jsonPath("$.bankAccount.snapshot").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(settlementDetail).doesNotContain(NUMBER_PRIMARY);

        String sellerDetail = mockMvc.perform(get(SELLER_URL + "/" + pid(S_PAID)).headers(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryBankAccount.accountNumberSuffix").value("1234"))
                .andExpect(jsonPath("$.bankAccounts[0].referencedBySettlement").value(true))
                .andExpect(jsonPath("$.bankAccounts[1].accountNumberSuffix").value("5678"))
                .andExpect(jsonPath("$.bankAccounts[1].referencedBySettlement").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(sellerDetail).doesNotContain(NUMBER_PRIMARY).doesNotContain(NUMBER_SECOND).doesNotContain("accountNumber\"");

        // 지급 재요청은 이미 PAID → 멱등 200(기존 동작 유지)
        mockMvc.perform(post("/api/v1/admin/settlements/" + SETTLEMENT_CONFIRMED + "/pay").headers(admin()))
                .andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private HttpHeaders admin() {
        return authHeaders.admin(ADMIN_ID);
    }

    private static String accountsUrl(long sellerId) {
        return SELLER_URL + "/" + pid(sellerId) + "/bank-accounts";
    }

    private static String accountUrl(long sellerId, long accountId) {
        return accountsUrl(sellerId) + "/" + accountId;
    }

    private static String primaryUrl(long sellerId, long accountId) {
        return accountUrl(sellerId, accountId) + "/primary";
    }

    private String registerBody(String bankCode, String number, String holder) throws Exception {
        return objectMapper.writeValueAsString(Map.of("bankCode", bankCode, "accountNumber", number, "accountHolder", holder));
    }

    private String updateBody(String bankCode, String number, String holder, String reason) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of("bankCode", bankCode, "accountNumber", number, "accountHolder", holder, "reason", reason));
    }

    private String reasonBody(String reason) throws Exception {
        return objectMapper.writeValueAsString(Map.of("reason", reason));
    }

    private JsonNode readJson(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private boolean isPrimary(long accountId) {
        return jdbc.queryForObject("SELECT is_primary FROM seller_bank_account WHERE id = ?", Boolean.class, accountId);
    }

    private String decryptedNumber(long accountId) {
        return bankAccountEncryptor.decrypt(
                jdbc.queryForObject("SELECT account_number FROM seller_bank_account WHERE id = ?", String.class, accountId));
    }

    private List<Map<String, Object>> audits(long accountId) {
        return jdbc.queryForList("SELECT action, diff_json FROM audit_log WHERE target_type = 'SETTLEMENT_BANK_ACCOUNT' "
                + "AND target_id = ? AND actor_user_id = ? ORDER BY id", accountId, ADMIN_ID);
    }

    private static String pid(long sellerId) {
        return "slr_" + ("89FS" + sellerId + "00000000000000000000000000").substring(0, 26);
    }

    // ---------- seed (모든 INSERT는 ? positional 바인딩) ----------

    private void seed() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSeller(S_EMPTY, "89F셀러빈");
                seedSeller(S_PAID, "89F셀러지급");
                seedSeller(S_OTHER, "89F셀러타");
                seedAccount(ACCOUNT_PAID_PRIMARY, S_PAID, "KB", NUMBER_PRIMARY, 1);
                seedAccount(ACCOUNT_PAID_SECOND, S_PAID, "SHINHAN", NUMBER_SECOND, 0);
                seedAccount(ACCOUNT_OTHER, S_OTHER, "KB", NUMBER_OTHER, 1);
                seedSettlement(SETTLEMENT_PAID, S_PAID, "PAID", ACCOUNT_PAID_PRIMARY, 7);
                seedSettlement(SETTLEMENT_CONFIRMED, S_PAID, "CONFIRMED", null, 8);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSeller(long id, String companyName) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', 'ACTIVE', NOW(6), NOW(6))", id, pid(id), companyName);
    }

    private void seedAccount(long id, long sellerId, String bankCode, String plainNumber, int isPrimary) {
        jdbc.update("INSERT INTO seller_bank_account (id, seller_id, bank_code, account_number, account_holder, is_primary, "
                + "status, verified_at, created_at, updated_at) VALUES (?, ?, ?, ?, '대표', ?, 'VERIFIED', NOW(6), NOW(6), NOW(6))",
                id, sellerId, bankCode, bankAccountEncryptor.encrypt(plainNumber), isPrimary);
    }

    private void seedSettlement(long id, long sellerId, String status, Long bankAccountId, int month) {
        java.time.LocalDate periodStart = java.time.LocalDate.of(2026, month, 1);
        java.time.LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        jdbc.update("INSERT INTO settlement (id, seller_id, bank_account_id, period_start, period_end, gross_amount, fee_amount, "
                + "refund_amount, net_amount, status, paid_at, scheduled_pay_date, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, 10000, 1000, 0, 9000, ?, ?, ?, NOW(6), NOW(6))",
                id, sellerId, bankAccountId, Timestamp.valueOf(periodStart.atStartOfDay()),
                Timestamp.valueOf(periodEnd.atTime(23, 59, 59)), status,
                "PAID".equals(status) ? Timestamp.valueOf("2026-08-20 10:00:00") : null,
                java.sql.Date.valueOf(periodEnd.plusDays(20)));
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE actor_user_id = ?", ADMIN_ID);
                jdbc.update("DELETE FROM settlement WHERE id BETWEEN 8851 AND 8859");
                jdbc.update("DELETE FROM seller_bank_account WHERE seller_id BETWEEN 8801 AND 8803");
                jdbc.update("DELETE FROM seller WHERE id BETWEEN 8801 AND 8803");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}
