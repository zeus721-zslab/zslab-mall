package com.zslab.mall.productquestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 셀러 상품 질문 답안 초안 GET 통합 테스트(D-253 · 실 MariaDB): 같은 상품 답변 Q&A 근거(점수 > 0 · 최신 상위 3 · 자기 자신 제외) · FAQ 근거 · 근거 없음
 * null · 소유·삭제 가드 404 · 인가.
 *
 * <p>근거 토큰은 V42 초기 FAQ와 겹치지 않는 고유 문자열이다. 관리자 쿠키 거부는 같은 요청이 셀러 쿠키로 200임을 먼저 확인한다(엔드포인트 부재 false-green
 * 방지).
 */
@AutoConfigureMockMvc
class SellerProductQuestionAnswerDraftIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10980L;
    private static final long BAND_TO = 10999L;
    private static final long BUYER = 10980L;
    private static final long ADMIN = 10981L;
    private static final long SELLER = 10982L;
    private static final long OTHER_SELLER = 10983L;
    private static final long SUSPENDED_SELLER = 10984L;
    private static final long SELLER_USER = 10985L;
    private static final long OTHER_SELLER_USER = 10986L;
    private static final long SUSPENDED_SELLER_USER = 10987L;
    private static final long PRODUCT = 10988L;
    private static final long OTHER_PRODUCT = 10989L;
    private static final long SUSPENDED_PRODUCT = 10990L;
    private static final long EMPTY_PRODUCT = 10991L;
    private static final long FAQ = 10992L;
    /** 질문 id는 대역 밖 자유 값(정리는 상품·구매자 대역 기준). */
    private static final long QUESTION_BASE = 10980_000L;
    private static final String TOKENS = "초안큐토큰가 초안큐토큰나";
    private static final String FAQ_ANSWER = "초안큐토큰가 FAQ 안내입니다.";
    private static final String NO_MATCH_CONTENT = "zqxjvk wqpzmn";
    private static final String URL = "/api/v1/seller/product-questions/%s/answer-draft";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private ProductQuestionFixture fixture;
    private LocalDateTime base;
    private long nextQuestionId;

    @BeforeEach
    void setUp() {
        fixture = new ProductQuestionFixture(jdbc, txManager);
        cleanup();
        fixture.seedUser(BUYER);
        fixture.seedUser(ADMIN);
        fixture.seedCatalog(SELLER, "ACTIVE", PRODUCT, null);
        fixture.seedProduct(EMPTY_PRODUCT, SELLER, "SALE", null);
        fixture.seedCatalog(OTHER_SELLER, "ACTIVE", OTHER_PRODUCT, null);
        fixture.seedCatalog(SUSPENDED_SELLER, "SUSPENDED", SUSPENDED_PRODUCT, null);
        fixture.seedSellerUser(SELLER_USER, SELLER);
        fixture.seedSellerUser(OTHER_SELLER_USER, OTHER_SELLER);
        fixture.seedSellerUser(SUSPENDED_SELLER_USER, SUSPENDED_SELLER);
        base = LocalDateTime.now().minusDays(10);
        nextQuestionId = QUESTION_BASE;
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("Q1 답변 Q&A 근거: 일치 4건 중 최신 3건(불일치·미답변·숨김·다른 상품·자기 자신 제외) → FAQ 순 · Q&A 템플릿 · 1000자 이내")
    void answeredQuestionEvidence() throws Exception {
        insertFaq();
        insertQuestion(PRODUCT, TOKENS + " 첫째", "VISIBLE", "답변 1");
        insertQuestion(PRODUCT, TOKENS + " 둘째", "VISIBLE", "답변 2");
        insertQuestion(PRODUCT, TOKENS + " 셋째", "VISIBLE", "답변 3");
        insertQuestion(PRODUCT, TOKENS + " 넷째", "VISIBLE", "답변 4");
        insertQuestion(PRODUCT, NO_MATCH_CONTENT, "VISIBLE", "무관 답변");
        insertQuestion(PRODUCT, TOKENS + " 미답변", "VISIBLE", null);
        insertQuestion(PRODUCT, TOKENS + " 숨김", "HIDDEN", "숨김 답변");
        insertQuestion(OTHER_PRODUCT, TOKENS + " 다른 상품", "VISIBLE", "다른 상품 답변");
        String target = insertQuestion(PRODUCT, TOKENS + " 대상", "VISIBLE", "대상 자신의 기존 답변");

        String body = mockMvc.perform(get(URL.formatted(target)).with(authHeaders.seller(SELLER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.length()").value(4))
                .andExpect(jsonPath("$.evidence[0].kind").value("ANSWERED_QUESTION"))
                .andExpect(jsonPath("$.evidence[0].title").value(TOKENS + " 넷째"))
                .andExpect(jsonPath("$.evidence[0].summary").value("답변 4"))
                .andExpect(jsonPath("$.evidence[1].title").value(TOKENS + " 셋째"))
                .andExpect(jsonPath("$.evidence[2].title").value(TOKENS + " 둘째"))
                .andExpect(jsonPath("$.evidence[3].kind").value("FAQ"))
                .andExpect(jsonPath("$.evidence[3].summary").value(FAQ_ANSWER))
                .andExpect(jsonPath("$.draft").value(Matchers.startsWith("안녕하세요, 고객님. 상품에 관심 가져 주셔서 감사합니다.")))
                .andExpect(jsonPath("$.draft").value(Matchers.containsString("- 답변 4\n- 답변 3\n- 답변 2\n- " + FAQ_ANSWER)))
                .andExpect(jsonPath("$.faqCandidate").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("대상 자신의 기존 답변", "무관 답변", "숨김 답변", "다른 상품 답변");
        String draft = JsonPath.read(body, "$.draft");
        assertThat(draft.length()).as("답변 한도(ProductQuestionAnswerRequest @Size max 1000) 안").isLessThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("Q2 FAQ 근거만: 답변 Q&A 없는 상품 → FAQ 1건 초안 · Q&A는 FAQ 후보 항상 false")
    void faqEvidenceOnly() throws Exception {
        insertFaq();
        String target = insertQuestion(EMPTY_PRODUCT, TOKENS, "VISIBLE", null);
        mockMvc.perform(get(URL.formatted(target)).with(authHeaders.seller(SELLER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.length()").value(1))
                .andExpect(jsonPath("$.evidence[0].kind").value("FAQ"))
                .andExpect(jsonPath("$.draft").value(Matchers.containsString("- " + FAQ_ANSWER)))
                .andExpect(jsonPath("$.faqCandidate").value(false));
    }

    @Test
    @DisplayName("Q3 근거 없음: 불일치 본문 → draft null(키 유지)·근거 0·FAQ 후보 false")
    void noEvidence_nullDraft() throws Exception {
        insertQuestion(PRODUCT, TOKENS + " 다른 질문", "VISIBLE", "다른 답변");
        String target = insertQuestion(PRODUCT, NO_MATCH_CONTENT, "VISIBLE", null);
        String body = mockMvc.perform(get(URL.formatted(target)).with(authHeaders.seller(SELLER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence").isEmpty())
                .andExpect(jsonPath("$.faqCandidate").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"draft\":null");
    }

    @Test
    @DisplayName("Q5 일치 비율(P-02): 질의 4토큰 중 1토큰 일치 답변 Q&A → 근거 제외 · 2토큰(0.5) 일치 → 근거 유지")
    void answeredQuestionEvidence_requiresHalfTokenMatch() throws Exception {
        insertQuestion(PRODUCT, TOKENS + " 첫째", "VISIBLE", "답변 1");
        String oneOfFour = insertQuestion(PRODUCT, "초안큐토큰가 zqxjvk wqpzmn qwmzpx", "VISIBLE", null);
        mockMvc.perform(get(URL.formatted(oneOfFour)).with(authHeaders.seller(SELLER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence").isEmpty());

        String twoOfFour = insertQuestion(PRODUCT, TOKENS + " zqxjvk wqpzmn", "VISIBLE", null);
        mockMvc.perform(get(URL.formatted(twoOfFour)).with(authHeaders.seller(SELLER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.length()").value(1))
                .andExpect(jsonPath("$.evidence[0].kind").value("ANSWERED_QUESTION"))
                .andExpect(jsonPath("$.evidence[0].summary").value("답변 1"));
    }

    @Test
    @DisplayName("Q4 404: 다른 셀러 상품 질문 · 삭제 질문 · 삭제 상품 질문 · 없는 질문 → PRODUCT_QUESTION_NOT_FOUND(셀러 200 대조)")
    void ownershipAndDeletion_notFound() throws Exception {
        String own = insertQuestion(PRODUCT, TOKENS, "VISIBLE", null);
        String otherSeller = insertQuestion(OTHER_PRODUCT, TOKENS, "VISIBLE", null);
        String deleted = insertQuestion(PRODUCT, TOKENS, "VISIBLE", null);
        fixture.markQuestionDeleted(nextQuestionId - 1);
        String deletedProduct = insertQuestion(EMPTY_PRODUCT, TOKENS, "VISIBLE", null);
        jdbc.update("UPDATE product SET deleted_at = NOW(6) WHERE id = ?", EMPTY_PRODUCT);

        mockMvc.perform(get(URL.formatted(own)).with(authHeaders.seller(SELLER_USER))).andExpect(status().isOk());
        for (String questionPid : new String[] {otherSeller, deleted, deletedProduct, ProductQuestionFixture.pid("pqn_", "NONE")}) {
            mockMvc.perform(get(URL.formatted(questionPid)).with(authHeaders.seller(SELLER_USER)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PRODUCT_QUESTION_NOT_FOUND"));
        }
        mockMvc.perform(get(URL.formatted(otherSeller)).with(authHeaders.seller(OTHER_SELLER_USER))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Q5 인가: 셀러 200(대조) → 무쿠키 401 · 관리자 쿠키 401 · 구매자 쿠키 401 / 정지 셀러 조회 200(목록과 같은 규칙)")
    void authorization() throws Exception {
        String own = insertQuestion(PRODUCT, TOKENS, "VISIBLE", null);
        mockMvc.perform(get(URL.formatted(own)).with(authHeaders.seller(SELLER_USER))).andExpect(status().isOk());
        mockMvc.perform(get(URL.formatted(own))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL.formatted(own)).with(authHeaders.admin(ADMIN))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL.formatted(own)).with(authHeaders.buyer(BUYER))).andExpect(status().isUnauthorized());

        String suspended = insertQuestion(SUSPENDED_PRODUCT, TOKENS, "VISIBLE", null);
        mockMvc.perform(get("/api/v1/seller/product-questions").with(authHeaders.seller(SUSPENDED_SELLER_USER))).andExpect(status().isOk());
        mockMvc.perform(get(URL.formatted(suspended)).with(authHeaders.seller(SUSPENDED_SELLER_USER))).andExpect(status().isOk());
    }

    /** 질문 1건(작성 시각은 삽입 순서대로 1분씩 늦게 · 답변이 있으면 답변자 = 셀러 사용자). */
    private String insertQuestion(long productId, String content, String questionStatus, String answer) {
        long questionId = nextQuestionId++;
        return fixture.insertQuestion(questionId, productId, BUYER, content, questionStatus, answer, answer == null ? null : SELLER_USER,
                base.plusMinutes(questionId - QUESTION_BASE));
    }

    private void insertFaq() {
        fixture.withoutForeignKeys(() -> jdbc.update("INSERT INTO faq (id, category, question, answer, sort_order, visible, created_at, "
                + "updated_at) VALUES (?, 'REVIEW_QUESTION', '초안큐토큰가 질문', ?, 999, TRUE, NOW(6), NOW(6))", FAQ, FAQ_ANSWER));
    }

    private void cleanup() {
        fixture.withoutForeignKeys(() -> {
            jdbc.update("DELETE FROM faq WHERE id BETWEEN ? AND ?", BAND_FROM, BAND_TO);
            jdbc.update("UPDATE product SET deleted_at = NULL WHERE id BETWEEN ? AND ?", BAND_FROM, BAND_TO);
        });
        fixture.cleanup(BAND_FROM, BAND_TO);
    }
}
