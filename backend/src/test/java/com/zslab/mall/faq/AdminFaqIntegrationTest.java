package com.zslab.mall.faq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.faq.enums.FaqCategory;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 관리자 FAQ 등록·수정·삭제·정렬 통합 테스트(Track 106-3 · 실 MariaDB · HTTP 경유).
 *
 * <p><b>격리</b>: V42 기본 FAQ가 상주하므로 "이 테스트가 만든 행"(질문이 {@link #MARKER}로 시작)만 단언한다. 정렬은 카테고리 전체 id가
 * 필요해 현재 순서를 읽어 이 테스트 행 둘의 자리만 바꾼 뒤 원래 순서로 되돌린다(V42 행 순서 불변). 인가 대조는 역할 쿠키만 바꾼 같은 요청으로
 * 한다 — 다른 역할 쿠키는 이 경로에서 인증되지 않아 401이고, 같은 요청을 관리자로 보내면 200·201이라 엔드포인트 부재로 인한 401과 구별된다.
 * 모든 SQL은 ? positional 바인딩·정적 SQL이다.
 */
@AutoConfigureMockMvc
class AdminFaqIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/faqs";
    private static final String ORDER_URL = "/api/v1/admin/faqs/order";
    private static final String MARKER = "트랙일공육삼관리";
    private static final long ADMIN_ID = 10870L;
    private static final long BUYER_ID = 10871L;
    private static final long SELLER_ID = 10872L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("A1 인가: 비인증·구매자·셀러 401 / 같은 요청 관리자 200·201(목록·등록)")
    void authorization() throws Exception {
        String body = body(FaqCategory.CLAIM, MARKER + " 인가", "답", true);
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).with(authHeaders.buyer(BUYER_ID))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).with(authHeaders.seller(SELLER_ID))).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).with(authHeaders.buyer(BUYER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).with(authHeaders.seller(SELLER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertThat(count("SELECT COUNT(*) FROM faq WHERE question = ?", MARKER + " 인가")).isZero();

        mockMvc.perform(get(URL).with(authHeaders.admin(ADMIN_ID))).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mockMvc.perform(post(URL).with(authHeaders.admin(ADMIN_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    @DisplayName("A2 등록: 카테고리 끝 배치 · trim · 숨김도 관리자 목록에 포함(공개 목록 제외)")
    void create_appendsToCategoryEnd() throws Exception {
        int nextBefore = nextSortOrder(FaqCategory.DELIVERY);
        long first = create(FaqCategory.DELIVERY, "  " + MARKER + " 첫째  ", "  답 첫째  ", true);
        long hidden = create(FaqCategory.DELIVERY, MARKER + " 숨김", "답 숨김", false);

        JsonNode firstRow = adminRow(first);
        assertThat(firstRow.get("sortOrder").asInt()).isEqualTo(nextBefore);
        assertThat(firstRow.get("question").asString()).isEqualTo(MARKER + " 첫째");
        assertThat(firstRow.get("answer").asString()).isEqualTo("답 첫째");
        assertThat(firstRow.get("updatedAt").asString()).isNotBlank();
        JsonNode hiddenRow = adminRow(hidden);
        assertThat(hiddenRow.get("sortOrder").asInt()).isEqualTo(nextBefore + 1);
        assertThat(hiddenRow.get("visible").asBoolean()).isFalse();

        List<Long> publicIds = new ArrayList<>();
        readJson(mockMvc.perform(get("/api/v1/faqs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .forEach(item -> publicIds.add(item.get("id").asLong()));
        assertThat(publicIds).contains(first).doesNotContain(hidden);
    }

    @Test
    @DisplayName("A3 수정: 전체 치환 반영 · 감사 UPDATE 1행 · 값 무변경 재요청 감사 0 · 미존재 404 FAQ_NOT_FOUND")
    void update_audit_noop_notFound() throws Exception {
        long faqId = create(FaqCategory.ACCOUNT, MARKER + " 수정 전", "답 수정 전", true);
        String changed = body(FaqCategory.ACCOUNT, MARKER + " 수정 후", "답 수정 후", false);

        mockMvc.perform(put(URL + "/" + faqId).with(authHeaders.admin(ADMIN_ID)).contentType(MediaType.APPLICATION_JSON)
                .content(changed)).andExpect(status().isNoContent());
        JsonNode row = adminRow(faqId);
        assertThat(row.get("question").asString()).isEqualTo(MARKER + " 수정 후");
        assertThat(row.get("answer").asString()).isEqualTo("답 수정 후");
        assertThat(row.get("visible").asBoolean()).isFalse();
        assertThat(auditCount("UPDATE", faqId)).isEqualTo(1);
        String diff = jdbc.queryForObject("SELECT diff_json FROM audit_log WHERE target_type = 'FAQ' AND action = 'UPDATE' "
                + "AND target_id = ?", String.class, faqId);
        assertThat(diff).contains("수정 전").contains("수정 후").contains("visible");

        mockMvc.perform(put(URL + "/" + faqId).with(authHeaders.admin(ADMIN_ID)).contentType(MediaType.APPLICATION_JSON)
                .content(changed)).andExpect(status().isNoContent());
        assertThat(auditCount("UPDATE", faqId)).isEqualTo(1);

        mockMvc.perform(put(URL + "/999999999").with(authHeaders.admin(ADMIN_ID)).contentType(MediaType.APPLICATION_JSON)
                .content(changed)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FAQ_NOT_FOUND"));
    }

    @Test
    @DisplayName("A4 카테고리 이동: 새 카테고리 끝으로 배치")
    void update_categoryChange_movesToEnd() throws Exception {
        long faqId = create(FaqCategory.CLAIM, MARKER + " 이동", "답 이동", true);
        int accountNext = nextSortOrder(FaqCategory.ACCOUNT);

        mockMvc.perform(put(URL + "/" + faqId).with(authHeaders.admin(ADMIN_ID)).contentType(MediaType.APPLICATION_JSON)
                .content(body(FaqCategory.ACCOUNT, MARKER + " 이동", "답 이동", true))).andExpect(status().isNoContent());

        JsonNode row = adminRow(faqId);
        assertThat(row.get("category").asString()).isEqualTo("ACCOUNT");
        assertThat(row.get("sortOrder").asInt()).isEqualTo(accountNext);
    }

    @Test
    @DisplayName("A5 삭제: soft delete 204 · 감사 DELETE 1행 · 목록 제외 · 재삭제 404")
    void delete_soft_audit() throws Exception {
        long faqId = create(FaqCategory.REVIEW_QUESTION, MARKER + " 삭제", "답 삭제", true);

        mockMvc.perform(delete(URL + "/" + faqId).with(authHeaders.admin(ADMIN_ID))).andExpect(status().isNoContent());

        assertThat(count("SELECT COUNT(*) FROM faq WHERE id = ? AND deleted_at IS NOT NULL", faqId)).isEqualTo(1);
        assertThat(auditCount("DELETE", faqId)).isEqualTo(1);
        assertThat(findAdminRow(faqId)).isNull();
        mockMvc.perform(delete(URL + "/" + faqId).with(authHeaders.admin(ADMIN_ID))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A6 정렬: 카테고리 전체 id 배열 index 반영 · 누락·중복·다른 카테고리 id 400 · 원래 순서 복원")
    void reorder_withinCategory() throws Exception {
        long first = create(FaqCategory.ORDER_PAYMENT, MARKER + " 정렬1", "답", true);
        long second = create(FaqCategory.ORDER_PAYMENT, MARKER + " 정렬2", "답", false);
        long otherCategory = create(FaqCategory.DELIVERY, MARKER + " 다른 카테고리", "답", true);
        List<Long> original = categoryIds(FaqCategory.ORDER_PAYMENT);
        try {
            List<Long> swapped = new ArrayList<>(original);
            swapped.set(original.indexOf(first), second);
            swapped.set(original.indexOf(second), first);

            mockMvc.perform(reorder(FaqCategory.ORDER_PAYMENT, swapped)).andExpect(status().isNoContent());
            assertThat(categoryIds(FaqCategory.ORDER_PAYMENT)).containsExactlyElementsOf(swapped);
            assertThat(adminRow(second).get("sortOrder").asInt()).isEqualTo(original.indexOf(first));

            List<Long> missing = new ArrayList<>(original);
            missing.remove(first);
            mockMvc.perform(reorder(FaqCategory.ORDER_PAYMENT, missing)).andExpect(status().isBadRequest());
            List<Long> duplicated = new ArrayList<>(original);
            duplicated.set(original.indexOf(first), second);
            mockMvc.perform(reorder(FaqCategory.ORDER_PAYMENT, duplicated)).andExpect(status().isBadRequest());
            List<Long> foreign = new ArrayList<>(original);
            foreign.add(otherCategory);
            mockMvc.perform(reorder(FaqCategory.ORDER_PAYMENT, foreign)).andExpect(status().isBadRequest());
            assertThat(categoryIds(FaqCategory.ORDER_PAYMENT)).containsExactlyElementsOf(swapped);
        } finally {
            mockMvc.perform(reorder(FaqCategory.ORDER_PAYMENT, original)).andExpect(status().isNoContent());
        }
    }

    @Test
    @DisplayName("A7 검증: question 201자·answer 공백·category 오값·visible 누락 400 VALIDATION_FAILED")
    void validation() throws Exception {
        List<String> invalidBodies = List.of(
                body(FaqCategory.CLAIM, "가".repeat(201), "답", true),
                body(FaqCategory.CLAIM, MARKER + " 검증", "   ", true),
                objectMapper.writeValueAsString(Map.of("category", "UNKNOWN", "question", MARKER + " 검증", "answer", "답",
                        "visible", true)),
                objectMapper.writeValueAsString(Map.of("category", "CLAIM", "question", MARKER + " 검증", "answer", "답")));
        for (String invalid : invalidBodies) {
            mockMvc.perform(post(URL).with(authHeaders.admin(ADMIN_ID)).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(post(URL).with(authHeaders.admin(ADMIN_ID)).contentType(MediaType.APPLICATION_JSON)
                .content(body(FaqCategory.CLAIM, MARKER + " 검증", "가".repeat(2001), true))).andExpect(status().isBadRequest());
    }

    private long create(FaqCategory category, String question, String answer, boolean visible) throws Exception {
        String response = mockMvc.perform(post(URL).with(authHeaders.admin(ADMIN_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(category, question, answer, visible)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return readJson(response).get("id").asLong();
    }

    private org.springframework.test.web.servlet.RequestBuilder reorder(FaqCategory category, List<Long> faqIds)
            throws Exception {
        return patch(ORDER_URL).with(authHeaders.admin(ADMIN_ID)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("category", category.name(), "faqIds", faqIds)));
    }

    private String body(FaqCategory category, String question, String answer, boolean visible) throws Exception {
        return objectMapper.writeValueAsString(Map.of("category", category.name(), "question", question, "answer", answer,
                "visible", visible));
    }

    private JsonNode adminList() throws Exception {
        return readJson(mockMvc.perform(get(URL).with(authHeaders.admin(ADMIN_ID))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    private JsonNode findAdminRow(long faqId) throws Exception {
        for (JsonNode item : adminList()) {
            if (item.get("id").asLong() == faqId) {
                return item;
            }
        }
        return null;
    }

    private JsonNode adminRow(long faqId) throws Exception {
        JsonNode row = findAdminRow(faqId);
        assertThat(row).as("관리자 목록에 faqId=%s 행이 있어야 한다", faqId).isNotNull();
        return row;
    }

    /** 관리자 목록 순서 그대로의 카테고리 id(숨김 포함). */
    private List<Long> categoryIds(FaqCategory category) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : adminList()) {
            if (category.name().equals(item.get("category").asString())) {
                ids.add(item.get("id").asLong());
            }
        }
        return ids;
    }

    private int nextSortOrder(FaqCategory category) {
        return count("SELECT COALESCE(MAX(sort_order) + 1, 0) FROM faq WHERE category = ? AND deleted_at IS NULL",
                category.name());
    }

    private int auditCount(String action, long faqId) {
        return count("SELECT COUNT(*) FROM audit_log WHERE target_type = 'FAQ' AND action = ? AND target_id = ?", action, faqId);
    }

    private JsonNode readJson(String json) {
        return objectMapper.readTree(json);
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private void cleanup() {
        jdbc.update("DELETE FROM audit_log WHERE target_type = 'FAQ' AND target_id IN (SELECT id FROM faq WHERE question LIKE ?)",
                MARKER + "%");
        jdbc.update("DELETE FROM faq WHERE question LIKE ?", MARKER + "%");
    }
}
