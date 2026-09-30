package com.zslab.mall.productquestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 셀러 답변·관리자 숨김 통합 테스트(Track 106-2·실 MariaDB): 셀러 목록(자기 상품·답변 여부 필터·오래된 순)·답변 규칙(D-239 규칙 6)·관리자
 * 숨김(규칙 7 — 사유 필수·감사·해제)·V41 답변 컬럼 CHECK.
 *
 * <p>비셀러·비관리자 판정은 역할 쿠키 경로 규칙상 401이다(D-235). 역할 가드가 걸렸는지는 같은 요청을 올바른 역할 쿠키로 보냈을 때 200/204가 되는
 * 것으로 대조한다(엔드포인트 부재 false-green 방지).
 */
@AutoConfigureMockMvc
class ProductQuestionSellerAdminIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10840L;
    private static final long BAND_TO = 10859L;
    private static final long BUYER = 10841L;
    private static final long SELLER = 10842L;
    private static final long OTHER_SELLER = 10843L;
    private static final long SUSPENDED_SELLER = 10844L;
    private static final long SELLER_USER = 10845L;
    private static final long OTHER_SELLER_USER = 10846L;
    private static final long SUSPENDED_SELLER_USER = 10847L;
    private static final long ADMIN = 10848L;
    private static final long PRODUCT = 10849L;
    private static final long OTHER_PRODUCT = 10850L;
    private static final long SUSPENDED_PRODUCT = 10851L;
    private static final long QUESTION_OLD = 10852L;
    private static final long QUESTION_NEW = 10853L;
    private static final long QUESTION_ANSWERED = 10854L;
    private static final long QUESTION_HIDDEN = 10855L;
    private static final long QUESTION_OTHER_SELLER = 10856L;
    private static final long QUESTION_SUSPENDED = 10857L;
    private static final String SELLER_URL = "/api/v1/seller/product-questions";
    private static final String ADMIN_URL = "/api/v1/admin/product-questions";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private ProductQuestionFixture fixture;
    private String oldPid;
    private String newPid;
    private String answeredPid;
    private String hiddenPid;
    private String otherSellerPid;
    private String suspendedPid;

    @BeforeEach
    void setUp() {
        fixture = new ProductQuestionFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(BUYER);
        fixture.seedUser(ADMIN);
        fixture.seedCatalog(SELLER, "ACTIVE", PRODUCT, null);
        fixture.seedCatalog(OTHER_SELLER, "ACTIVE", OTHER_PRODUCT, null);
        fixture.seedCatalog(SUSPENDED_SELLER, "SUSPENDED", SUSPENDED_PRODUCT, null);
        fixture.seedSellerUser(SELLER_USER, SELLER);
        fixture.seedSellerUser(OTHER_SELLER_USER, OTHER_SELLER);
        fixture.seedSellerUser(SUSPENDED_SELLER_USER, SUSPENDED_SELLER);
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        oldPid = fixture.insertQuestion(QUESTION_OLD, PRODUCT, BUYER, "오래된 미답변 질문", "VISIBLE", null, null, base);
        newPid = fixture.insertQuestion(QUESTION_NEW, PRODUCT, BUYER, "최근 미답변 질문", "VISIBLE", null, null, base.plusHours(2));
        answeredPid = fixture.insertQuestion(QUESTION_ANSWERED, PRODUCT, BUYER, "답변된 질문입니다", "VISIBLE", "기존 답변", SELLER_USER,
                base.plusHours(1));
        hiddenPid = fixture.insertQuestion(QUESTION_HIDDEN, PRODUCT, BUYER, "숨김 질문입니다", "HIDDEN", null, null, base.plusHours(3));
        otherSellerPid = fixture.insertQuestion(QUESTION_OTHER_SELLER, OTHER_PRODUCT, BUYER, "다른 셀러 질문", "VISIBLE", null, null,
                base.plusHours(4));
        suspendedPid = fixture.insertQuestion(QUESTION_SUSPENDED, SUSPENDED_PRODUCT, BUYER, "정지 셀러 질문", "VISIBLE", null, null,
                base.plusHours(5));
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("S1 셀러 목록: 기본 미답변·오래된 순·자기 상품의 공개 질문만 / ANSWERED·ALL 필터 / 오값 400 / 구매자 쿠키 401")
    void sellerList_filtersOwnVisibleQuestions() throws Exception {
        mockMvc.perform(get(SELLER_URL).with(authHeaders.seller(SELLER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[0].questionId").value(oldPid))
                .andExpect(jsonPath("$.items[1].questionId").value(newPid))
                .andExpect(jsonPath("$.items[0].productName").value("질문상품"));
        mockMvc.perform(get(SELLER_URL).param("answered", "ANSWERED").with(authHeaders.seller(SELLER_USER)))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].questionId").value(answeredPid))
                .andExpect(jsonPath("$.items[0].answerContent").value("기존 답변"));
        mockMvc.perform(get(SELLER_URL).param("answered", "ALL").with(authHeaders.seller(SELLER_USER)))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items[0].questionId").value(oldPid))
                .andExpect(jsonPath("$.items[1].questionId").value(answeredPid))
                .andExpect(jsonPath("$.items[2].questionId").value(newPid));
        mockMvc.perform(get(SELLER_URL).param("answered", "NONE").with(authHeaders.seller(SELLER_USER)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(SELLER_URL).with(authHeaders.buyer(BUYER))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S2 답변(규칙 6): 등록 204(답변 3컬럼) · 수정 덮어쓰기 / 다른 셀러 404 / 숨김 422 / 정지 셀러 403 / 형식 400 / 구매자 쿠키 401")
    void answer_followsOwnershipAndStateRules() throws Exception {
        mockMvc.perform(answerRequest(oldPid, "  내일 출고됩니다  ").with(authHeaders.seller(SELLER_USER))).andExpect(status().isNoContent());
        Map<String, Object> answered = answerColumns(QUESTION_OLD);
        assertThat(answered).containsEntry("answer_content", "내일 출고됩니다").containsEntry("answered_by", SELLER_USER);
        assertThat(answered.get("answered_at")).isNotNull();

        mockMvc.perform(answerRequest(answeredPid, "수정한 답변").with(authHeaders.seller(SELLER_USER))).andExpect(status().isNoContent());
        assertThat(answerColumns(QUESTION_ANSWERED)).containsEntry("answer_content", "수정한 답변");

        mockMvc.perform(answerRequest(otherSellerPid, "남의 상품 답변").with(authHeaders.seller(SELLER_USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_QUESTION_NOT_FOUND"));
        mockMvc.perform(answerRequest(hiddenPid, "숨김 질문 답변").with(authHeaders.seller(SELLER_USER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_QUESTION_INVALID_STATE"));
        mockMvc.perform(answerRequest(suspendedPid, "정지 셀러 답변").with(authHeaders.seller(SUSPENDED_SELLER_USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(answerRequest(newPid, "   ").with(authHeaders.seller(SELLER_USER))).andExpect(status().isBadRequest());
        mockMvc.perform(answerRequest(newPid, "가".repeat(1001)).with(authHeaders.seller(SELLER_USER))).andExpect(status().isBadRequest());
        mockMvc.perform(answerRequest(newPid, "구매자 답변").with(authHeaders.buyer(BUYER))).andExpect(status().isUnauthorized());

        assertThat(answerColumns(QUESTION_OTHER_SELLER).get("answer_content")).isNull();
        assertThat(answerColumns(QUESTION_HIDDEN).get("answer_content")).isNull();
        assertThat(answerColumns(QUESTION_SUSPENDED).get("answer_content")).isNull();
        assertThat(answerColumns(QUESTION_NEW).get("answer_content")).isNull();
    }

    @Test
    @DisplayName("A1 관리자 인가: 익명·셀러 쿠키 → 목록·숨김 401 / 같은 요청 관리자 → 200·204")
    void adminEndpoints_requireAdmin() throws Exception {
        mockMvc.perform(get(ADMIN_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ADMIN_URL).with(authHeaders.seller(SELLER_USER))).andExpect(status().isUnauthorized());
        mockMvc.perform(statusRequest(newPid, "HIDDEN", "광고").with(authHeaders.seller(SELLER_USER))).andExpect(status().isUnauthorized());
        assertThat(questionStatus(QUESTION_NEW)).isEqualTo("VISIBLE");

        mockMvc.perform(get(ADMIN_URL).with(authHeaders.admin(ADMIN))).andExpect(status().isOk());
        mockMvc.perform(statusRequest(newPid, "HIDDEN", "광고").with(authHeaders.admin(ADMIN))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("A2 숨김(규칙 7): 사유 누락·공백·201자 400 / 숨김 → 사유 trim·감사 {status}/{status, reason}·본문 없음 / 같은 상태 422 / 해제 → 사유 비움·감사 2행")
    void hideAndUnhide_requiresReasonAndAudits() throws Exception {
        mockMvc.perform(patch(ADMIN_URL + "/" + answeredPid + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"HIDDEN\"}").with(authHeaders.admin(ADMIN))).andExpect(status().isBadRequest());
        mockMvc.perform(statusRequest(answeredPid, "HIDDEN", "   ").with(authHeaders.admin(ADMIN))).andExpect(status().isBadRequest());
        mockMvc.perform(statusRequest(answeredPid, "HIDDEN", "가".repeat(201)).with(authHeaders.admin(ADMIN)))
                .andExpect(status().isBadRequest());
        assertThat(questionStatus(QUESTION_ANSWERED)).isEqualTo("VISIBLE");

        mockMvc.perform(statusRequest(answeredPid, "HIDDEN", "  광고성 질문  ").with(authHeaders.admin(ADMIN))).andExpect(status().isNoContent());
        assertThat(questionStatus(QUESTION_ANSWERED)).isEqualTo("HIDDEN");
        assertThat(hiddenReason(QUESTION_ANSWERED)).isEqualTo("광고성 질문");
        assertThat(jdbc.queryForMap("SELECT action, actor_user_id FROM audit_log WHERE target_type = 'PRODUCT_QUESTION' AND target_id = ?",
                QUESTION_ANSWERED))
                .containsEntry("action", "UPDATE")
                .containsEntry("actor_user_id", ADMIN);
        String diff = jdbc.queryForObject("SELECT diff_json FROM audit_log WHERE target_type = 'PRODUCT_QUESTION' AND target_id = ?",
                String.class, QUESTION_ANSWERED);
        assertThat(diff).contains("VISIBLE").contains("HIDDEN").contains("광고성 질문")
                .doesNotContain("답변된 질문입니다").doesNotContain("기존 답변");
        // 관리자 목록은 전역이라 건수 대신 이 질문이 HIDDEN 필터에 사유와 함께 나오는지만 본다.
        mockMvc.perform(get(ADMIN_URL).param("status", "HIDDEN").with(authHeaders.admin(ADMIN)))
                .andExpect(jsonPath("$.items[?(@.status != 'HIDDEN')]").isEmpty())
                .andExpect(jsonPath("$.items[?(@.questionId == '%s')].hiddenReason".formatted(answeredPid)).value("광고성 질문"));

        mockMvc.perform(statusRequest(answeredPid, "HIDDEN", "다시").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_QUESTION_INVALID_STATE"));

        mockMvc.perform(statusRequest(answeredPid, "VISIBLE", "오판 정정").with(authHeaders.admin(ADMIN))).andExpect(status().isNoContent());
        assertThat(questionStatus(QUESTION_ANSWERED)).isEqualTo("VISIBLE");
        assertThat(hiddenReason(QUESTION_ANSWERED)).as("숨김 해제 시 사유를 비운다").isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'PRODUCT_QUESTION' AND target_id = ?",
                Integer.class, QUESTION_ANSWERED)).isEqualTo(2);
        mockMvc.perform(statusRequest(ProductQuestionFixture.pid("pqn_", "NONE"), "HIDDEN", "없음").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("D1 V41 CHECK: 답변 3컬럼 중 일부만 채운 UPDATE → chk_product_question_answer 위반 / 셋 다 채우면 통과(대조)")
    void partialAnswerColumns_rejectedBySchema() {
        assertThatThrownBy(() -> jdbc.update("UPDATE product_question SET answer_content = '부분 답변' WHERE id = ?", QUESTION_NEW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_product_question_answer");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE product_question SET answer_content = '부분 답변', answered_at = NOW(6) WHERE id = ?", QUESTION_NEW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_product_question_answer");
        assertThat(answerColumns(QUESTION_NEW).get("answer_content")).isNull();

        assertThat(jdbc.update("UPDATE product_question SET answer_content = '전체 답변', answered_at = NOW(6), answered_by = ? WHERE id = ?",
                SELLER_USER, QUESTION_NEW)).isEqualTo(1);
    }

    // ---------- helpers ----------

    private MockHttpServletRequestBuilder answerRequest(String questionPublicId, String content) {
        return put(SELLER_URL + "/" + questionPublicId + "/answer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"%s\"}".formatted(content));
    }

    private MockHttpServletRequestBuilder statusRequest(String questionPublicId, String status, String reason) {
        return patch(ADMIN_URL + "/" + questionPublicId + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"%s\",\"reason\":\"%s\"}".formatted(status, reason));
    }

    private Map<String, Object> answerColumns(long questionId) {
        return jdbc.queryForMap("SELECT answer_content, answered_at, answered_by FROM product_question WHERE id = ?", questionId);
    }

    private String questionStatus(long questionId) {
        return jdbc.queryForObject("SELECT status FROM product_question WHERE id = ?", String.class, questionId);
    }

    private String hiddenReason(long questionId) {
        return jdbc.queryForObject("SELECT hidden_reason FROM product_question WHERE id = ?", String.class, questionId);
    }
}
