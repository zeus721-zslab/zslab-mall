package com.zslab.mall.faq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.faq.enums.FaqCategory;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 구매자 FAQ 공개 조회 통합 테스트(Track 106-3 · 실 MariaDB · HTTP 경유 · 비로그인).
 *
 * <p><b>격리</b>: V42 기본 FAQ가 상주하므로 "이 테스트가 넣은 행"(질문이 {@link #MARKER}로 시작)만 골라 단언한다. 즉시 답 질의도 V42 본문에 없는
 * 표식 단어를 써서 결과를 이 테스트 행으로 좁힌다. 모든 SQL은 ? positional 바인딩·정적 SQL이다.
 */
@AutoConfigureMockMvc
class FaqPublicIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/faqs";
    private static final String SUGGEST_URL = "/api/v1/faqs/suggest";
    private static final String MARKER = "트랙일공육삼공개";
    /** V42 본문에 없는 즉시 답 표식 단어. */
    private static final String KEYWORD = "표식단어공개";
    private static final String SECOND_KEYWORD = "보조단어공개";

    @Autowired
    private MockMvc mockMvc;
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
    @DisplayName("P1 비로그인: 목록 200 · 즉시 답 200(공개 경로 정확 매칭)")
    void anonymous_allowed() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mockMvc.perform(get(SUGGEST_URL).param("q", "배송 조회")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("P1-경계 비로그인: 정확 경로 밖(끝 슬래시·하위 경로·다른 메서드)은 공개되지 않는다 — P1 정확 경로 200과 대조")
    void anonymous_outsideExactPaths_rejected() throws Exception {
        Map<String, Integer> actual = new LinkedHashMap<>();
        actual.put("GET " + URL + "/", statusOf(get(URL + "/")));
        actual.put("GET " + URL + "/foo", statusOf(get(URL + "/foo")));
        actual.put("GET " + SUGGEST_URL + "/", statusOf(get(SUGGEST_URL + "/").param("q", "배송 조회")));
        actual.put("GET " + SUGGEST_URL + "/foo", statusOf(get(SUGGEST_URL + "/foo").param("q", "배송 조회")));
        actual.put("POST " + URL, statusOf(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}")));

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(Map.of(
                "GET " + URL + "/", 401,
                "GET " + URL + "/foo", 401,
                "GET " + SUGGEST_URL + "/", 401,
                "GET " + SUGGEST_URL + "/foo", 401,
                "POST " + URL, 401));
    }

    @Test
    @DisplayName("P2 목록: 숨김·삭제 제외 · 카테고리 선언 순서 → 카테고리 안 sortOrder 순")
    void list_excludesHiddenDeleted_ordered() throws Exception {
        long later = seed(FaqCategory.CLAIM, MARKER + " 뒤", 901, true, false);
        long earlier = seed(FaqCategory.CLAIM, MARKER + " 앞", 900, true, false);
        long first = seed(FaqCategory.ORDER_PAYMENT, MARKER + " 첫 카테고리", 999, true, false);
        long hidden = seed(FaqCategory.CLAIM, MARKER + " 숨김", 902, false, false);
        long deleted = seed(FaqCategory.CLAIM, MARKER + " 삭제", 903, true, true);

        JsonNode items = list();
        List<Long> ids = new ArrayList<>();
        List<Integer> categoryOrdinals = new ArrayList<>();
        for (JsonNode item : items) {
            ids.add(item.get("id").asLong());
            categoryOrdinals.add(FaqCategory.valueOf(item.get("category").asString()).ordinal());
        }
        assertThat(ids).contains(later, earlier, first).doesNotContain(hidden, deleted);
        assertThat(ids.indexOf(earlier)).isLessThan(ids.indexOf(later));
        // 카테고리 순서가 sortOrder보다 앞선다(ORDER_PAYMENT 999가 CLAIM 900보다 먼저)
        assertThat(ids.indexOf(first)).isLessThan(ids.indexOf(earlier));
        assertThat(categoryOrdinals).isSorted();
        JsonNode earlierItem = items.get(ids.indexOf(earlier));
        assertThat(earlierItem.get("question").asString()).isEqualTo(MARKER + " 앞");
        assertThat(earlierItem.get("answer").asString()).isNotBlank();
        assertThat(earlierItem.has("sortOrder")).isFalse();
    }

    @Test
    @DisplayName("P3 즉시 답 경계: q trim 후 1자·101자·누락 400 / 2자 200 / 2자 이상 토큰 없음 → 빈 배열")
    void suggest_boundaries() throws Exception {
        mockMvc.perform(get(SUGGEST_URL).param("q", "  세  ")).andExpect(status().isBadRequest());
        mockMvc.perform(get(SUGGEST_URL).param("q", "가".repeat(101))).andExpect(status().isBadRequest());
        mockMvc.perform(get(SUGGEST_URL)).andExpect(status().isBadRequest());
        mockMvc.perform(get(SUGGEST_URL).param("q", "배송")).andExpect(status().isOk());
        mockMvc.perform(get(SUGGEST_URL).param("q", "a b c")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("P4 즉시 답: 질문·답 모두 매칭 · 일치 토큰 많은 순 · 숨김·삭제 FAQ는 검색되지 않음")
    void suggest_ranksAndExcludesHidden() throws Exception {
        long questionHit = seed(FaqCategory.DELIVERY, MARKER + " " + KEYWORD + " 질문", 900, true, false);
        long bothHit = seedWithAnswer(FaqCategory.ACCOUNT, MARKER + " " + KEYWORD + " 둘 다", SECOND_KEYWORD + "가 답에 있습니다.", 900);
        long hidden = seed(FaqCategory.DELIVERY, MARKER + " " + KEYWORD + " 숨김", 901, false, false);
        long deleted = seed(FaqCategory.DELIVERY, MARKER + " " + KEYWORD + " 삭제", 902, true, true);

        JsonNode results = objectMapper.readTree(mockMvc.perform(get(SUGGEST_URL).param("q", KEYWORD + " " + SECOND_KEYWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        List<Long> ids = new ArrayList<>();
        results.forEach(item -> ids.add(item.get("id").asLong()));
        // 두 토큰 모두 일치(답의 "보조단어공개가" → 조사 뗀 형태 일치)가 한 토큰 일치보다 앞선다 — 카테고리 순서(DELIVERY < ACCOUNT)와 반대
        assertThat(ids).containsExactly(bothHit, questionHit).doesNotContain(hidden, deleted);
        assertThat(results.get(0).get("answer").asString()).isEqualTo(SECOND_KEYWORD + "가 답에 있습니다.");
    }

    private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private JsonNode list() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(URL)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private long seed(FaqCategory category, String question, int sortOrder, boolean visible, boolean deleted) {
        return insert(category, question, "테스트 답변입니다.", sortOrder, visible, deleted);
    }

    private long seedWithAnswer(FaqCategory category, String question, String answer, int sortOrder) {
        return insert(category, question, answer, sortOrder, true, false);
    }

    private long insert(FaqCategory category, String question, String answer, int sortOrder, boolean visible, boolean deleted) {
        // 모든 변수는 ? 바인딩 사용, SQL injection 위험 없음
        jdbc.update("INSERT INTO faq (category, question, answer, sort_order, visible, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6), ?)",
                category.name(), question, answer, sortOrder, visible, deleted ? LocalDateTime.now() : null);
        Long id = jdbc.queryForObject("SELECT id FROM faq WHERE question = ?", Long.class, question);
        return id;
    }

    private void cleanup() {
        jdbc.update("DELETE FROM faq WHERE question LIKE ?", MARKER + "%");
    }
}
