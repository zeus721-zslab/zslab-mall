package com.zslab.mall.reconciliation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 불일치 API 통합 테스트(Track 104-2 D-216·invariants P6·실 MariaDB). 인증·권한, 목록 필터, 해결(메모 필수·감사 기록·이미 해결 422·
 * 미존재 404), 주문 상세 불일치 섹션, 대시보드 처리 대기 수치를 본다.
 */
@AutoConfigureMockMvc
class AdminReconciliationIssueIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/reconciliation-issues";
    private static final long ADMIN_ID = 6701L;
    private static final long BUYER_ID = 6702L;
    private static final long ORDER_ID = 6701L;
    private static final String ORDER_PUBLIC_ID = "ord_rai_6701";
    private static final String DEDUPE_PREFIX = "rai-test:";
    private static final long MISSING_ISSUE_ID = 999_999_999L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private AuthHeaders authHeaders;

    private TransactionTemplate tx;
    private long openDriftIssueId;
    private long openUnmatchedIssueId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("INSERT INTO `user` (id, public_id, name, created_at, updated_at) VALUES (?, 'usr_rai_6701', '불일치 담당자', "
                    + "NOW(6), NOW(6))", ADMIN_ID);
            jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                    + "ordered_at, created_at, updated_at) VALUES (?, ?, ?, 'ORDRAI-6701', 'PAID', 10000, 0, 0, NOW(6), NOW(6), NOW(6))",
                    ORDER_ID, ORDER_PUBLIC_ID, BUYER_ID);
            jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                    + "total_price, item_status, created_at, updated_at, product_name, commission_rate) VALUES (?, 'oit_rai_6701', ?, 1, 1, "
                    + "1, 1, 10000, 10000, 'PAID', NOW(6), NOW(6), '불일치 관리 상품', 1000)", ORDER_ID, ORDER_ID);
            insertIssue("ITEM_STATE_DRIFT", DEDUPE_PREFIX + "drift", ORDER_ID, "OPEN");
            insertIssue("PG_UNMATCHED_CALLBACK", DEDUPE_PREFIX + "unmatched", null, "OPEN");
            insertIssue("PG_TID_CONFLICT", DEDUPE_PREFIX + "resolved", ORDER_ID, "RESOLVED");
        });
        openDriftIssueId = issueId(DEDUPE_PREFIX + "drift");
        openUnmatchedIssueId = issueId(DEDUPE_PREFIX + "unmatched");
    }

    @AfterEach
    void tearDown() {
        try {
            cleanup();
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    @Test
    @DisplayName("인증: 무토큰 401 · 구매자 403(목록·해결) · 관리자 200")
    void authorization() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(resolveRequest(openDriftIssueId, "확인").headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID))).andExpect(status().isOk());
        assertThat(issueStatus(openDriftIssueId)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("목록: 상태·유형 필터 · 주문 번호·public id 표시 · 매칭 주문 없는 통지는 주문 null · 잘못된 유형 값 400")
    void list_filters() throws Exception {
        mockMvc.perform(get(URL).param("status", "OPEN").param("type", "ITEM_STATE_DRIFT").param("size", "100")
                        .headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.issueId == " + openDriftIssueId + ")].orderNo").value("ORDRAI-6701"))
                .andExpect(jsonPath("$.items[?(@.issueId == " + openDriftIssueId + ")].orderId").value(ORDER_PUBLIC_ID))
                .andExpect(jsonPath("$.items[?(@.issueId == " + openDriftIssueId + ")].detail.reason").value("TEST_REASON"))
                .andExpect(jsonPath("$.items[?(@.issueType != 'ITEM_STATE_DRIFT')]").isEmpty())
                .andExpect(jsonPath("$.items[?(@.status != 'OPEN')]").isEmpty());
        mockMvc.perform(get(URL).param("type", "PG_UNMATCHED_CALLBACK").param("size", "100").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                // non_null 직렬화(application.yml)라 주문 없는 행은 orderId 키 자체가 없다
                .andExpect(jsonPath("$.items[?(@.issueId == " + openUnmatchedIssueId + ")].issueType").value(contains("PG_UNMATCHED_CALLBACK")))
                .andExpect(jsonPath("$.items[?(@.issueId == " + openUnmatchedIssueId + " && @.orderId)]").isEmpty());
        mockMvc.perform(get(URL).param("type", "NOT_A_TYPE").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("해결: 200·RESOLVED·처리자·메모·감사 1행 → 재해결 422 · 메모 누락 400 · 미존재 404 · 대시보드 열린 불일치 수 -1")
    void resolve_flow() throws Exception {
        long openBefore = dashboardReconciliationOpen();
        assertThat(openBefore).isEqualTo(openCountInDb());

        mockMvc.perform(resolveRequest(openDriftIssueId, "  수동 보정 완료  ").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedByName").value("불일치 담당자"))
                .andExpect(jsonPath("$.resolutionMemo").value("수동 보정 완료"));
        assertThat(issueStatus(openDriftIssueId)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT resolved_by FROM reconciliation_issue WHERE id = ?", Long.class, openDriftIssueId))
                .isEqualTo(ADMIN_ID);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'RECONCILIATION_ISSUE' AND target_id = ? "
                + "AND action = 'UPDATE' AND actor_user_id = ?", Integer.class, openDriftIssueId, ADMIN_ID)).isEqualTo(1);
        assertThat(dashboardReconciliationOpen()).isEqualTo(openBefore - 1);

        mockMvc.perform(resolveRequest(openDriftIssueId, "다시").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(resolveRequest(openUnmatchedIssueId, " ").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(resolveRequest(MISSING_ISSUE_ID, "없음").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNotFound());
        assertThat(issueStatus(openUnmatchedIssueId)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("주문 상세: 이 주문의 불일치(열림·해결 모두·최신순)만 섹션으로 내려간다")
    void orderDetail_includesOrderIssues() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders/" + ORDER_PUBLIC_ID).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationIssues", hasSize(2)))
                .andExpect(jsonPath("$.reconciliationIssues[0].issueType").value("PG_TID_CONFLICT"))
                .andExpect(jsonPath("$.reconciliationIssues[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$.reconciliationIssues[1].issueType").value("ITEM_STATE_DRIFT"));
    }

    // ---------- helpers(모든 변수 ? 바인딩) ----------

    private MockHttpServletRequestBuilder resolveRequest(long issueId, String memo) {
        return post(URL + "/" + issueId + "/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memo\":\"" + memo + "\"}");
    }

    private void insertIssue(String type, String dedupeKey, Long orderId, String status) {
        boolean resolved = "RESOLVED".equals(status);
        jdbc.update("INSERT INTO reconciliation_issue (issue_type, dedupe_key, order_id, detail, status, detected_at, resolved_at, "
                + "resolved_by, resolution_memo, created_at) VALUES (?, ?, ?, '{\"reason\":\"TEST_REASON\"}', ?, NOW(6), ?, ?, ?, NOW(6))",
                type, dedupeKey, orderId, status, resolved ? LocalDateTime.now() : null, resolved ? ADMIN_ID : null,
                resolved ? "시드 해결" : null);
    }

    private long issueId(String dedupeKey) {
        return jdbc.queryForObject("SELECT id FROM reconciliation_issue WHERE dedupe_key = ?", Long.class, dedupeKey);
    }

    private String issueStatus(long issueId) {
        return jdbc.queryForObject("SELECT status FROM reconciliation_issue WHERE id = ?", String.class, issueId);
    }

    private long openCountInDb() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_issue WHERE status = 'OPEN'", Long.class);
    }

    private long dashboardReconciliationOpen() throws Exception {
        String body = mockMvc.perform(get("/api/v1/admin/dashboard").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(body, "$.pending.reconciliationOpen").longValue();
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'RECONCILIATION_ISSUE' AND actor_user_id = ?", ADMIN_ID);
            jdbc.update("DELETE FROM reconciliation_issue WHERE dedupe_key LIKE ?", DEDUPE_PREFIX + "%");
            jdbc.update("DELETE FROM order_item WHERE order_id = ?", ORDER_ID);
            jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
            jdbc.update("DELETE FROM `user` WHERE id = ?", ADMIN_ID);
        });
    }
}
