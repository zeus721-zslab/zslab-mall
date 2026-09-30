package com.zslab.mall.productquestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.common.security.JwtAuthenticationToken;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
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
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 구매자 상품 질문 통합 테스트(Track 106-2·실 MariaDB): 인가(비로그인 401·셀러·관리자 차단)·등록·수정·삭제 상태 규칙(D-239 규칙 4·5)·내 질문.
 *
 * <p>셀러·관리자 차단: 역할 쿠키는 경로 접두사에 맞는 것만 읽으므로(D-235) 셀러·관리자 쿠키로 구매자 경로를 부르면 익명과 같은 401이다. 이것만으로는
 * 새 {@code hasRole("BUYER")} 매처를 검증하지 못하므로(매처가 없어도 401), SELLER·ADMIN 인증을 직접 주입해 403을 확인한다 — 매처가 없으면
 * 주입된 셀러 id가 구매자로 해석돼 질문이 만들어진다(RED 선증명 대상). 같은 요청을 구매자 쿠키로 보내 201이 되는 것으로 엔드포인트 부재
 * false-green을 막는다.
 */
@AutoConfigureMockMvc
class ProductQuestionBuyerIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10820L;
    private static final long BAND_TO = 10839L;
    private static final long BUYER = 10821L;
    private static final long OTHER_BUYER = 10822L;
    private static final long SELLER = 10823L;
    private static final long PRODUCT = 10824L;
    private static final long HIDDEN_PRODUCT = 10825L;
    private static final long QUESTION_OPEN = 10826L;
    private static final long QUESTION_ANSWERED = 10827L;
    private static final long QUESTION_HIDDEN = 10828L;
    private static final long SELLER_USER = 10829L;
    private static final long ADMIN = 10830L;
    private static final String BASE_URL = "/api/v1/product-questions";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private ProductQuestionFixture fixture;
    private String productPid;
    private String hiddenProductPid;
    private String openPid;
    private String answeredPid;
    private String hiddenPid;

    @BeforeEach
    void setUp() {
        fixture = new ProductQuestionFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(BUYER);
        fixture.seedUser(OTHER_BUYER);
        fixture.seedUser(ADMIN);
        productPid = fixture.seedCatalog(SELLER, "ACTIVE", PRODUCT, null);
        hiddenProductPid = fixture.seedProduct(HIDDEN_PRODUCT, SELLER, "HIDDEN", null);
        fixture.seedSellerUser(SELLER_USER, SELLER);
        LocalDateTime base = LocalDateTime.now().minusHours(3);
        openPid = fixture.insertQuestion(QUESTION_OPEN, PRODUCT, BUYER, "배송은 언제 되나요?", "VISIBLE", null, null, base);
        answeredPid = fixture.insertQuestion(QUESTION_ANSWERED, PRODUCT, BUYER, "사이즈가 어떻게 되나요?", "VISIBLE", "정사이즈입니다",
                SELLER_USER, base.plusHours(1));
        hiddenPid = fixture.insertQuestion(QUESTION_HIDDEN, PRODUCT, BUYER, "숨겨질 질문입니다", "HIDDEN", null, null, base.plusHours(2));
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("B1 비로그인 → 등록·수정·삭제·내 질문 401")
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(createRequest(productPid, "익명 질문입니다").with(authHeaders.csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(updateRequest(openPid, "익명 수정입니다").with(authHeaders.csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BASE_URL + "/" + openPid).with(authHeaders.csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE_URL + "/me")).andExpect(status().isUnauthorized());
        assertThat(questionCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("B2 셀러·관리자: 역할 쿠키 → 익명 401 · 인증 주입 → 403(hasRole BUYER) / 같은 등록을 구매자 쿠키로 → 201(대조)")
    void sellerAndAdmin_cannotWriteAsBuyer() throws Exception {
        mockMvc.perform(createRequest(productPid, "셀러 쿠키 질문").with(authHeaders.seller(SELLER_USER))).andExpect(status().isUnauthorized());
        mockMvc.perform(createRequest(productPid, "관리자 쿠키 질문").with(authHeaders.admin(ADMIN))).andExpect(status().isUnauthorized());

        mockMvc.perform(createRequest(productPid, "셀러 인증 질문").with(authHeaders.csrf())
                        .with(authentication(JwtAuthenticationToken.authenticated(SELLER_USER, ActorRole.SELLER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(createRequest(productPid, "관리자 인증 질문").with(authHeaders.csrf())
                        .with(authentication(JwtAuthenticationToken.authenticated(ADMIN, ActorRole.ADMIN))))
                .andExpect(status().isForbidden());
        mockMvc.perform(updateRequest(openPid, "셀러 인증 수정").with(authHeaders.csrf())
                        .with(authentication(JwtAuthenticationToken.authenticated(SELLER_USER, ActorRole.SELLER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE_URL + "/" + openPid).with(authHeaders.csrf())
                        .with(authentication(JwtAuthenticationToken.authenticated(ADMIN, ActorRole.ADMIN))))
                .andExpect(status().isForbidden());
        assertThat(questionCount()).isEqualTo(3);

        mockMvc.perform(createRequest(productPid, "구매자 쿠키 질문").with(authHeaders.buyer(BUYER))).andExpect(status().isCreated());
        assertThat(questionCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("B3 등록: 성공(trim 저장·VISIBLE·미답변) / trim 후 4자·501자 400 / 비노출 상품 404")
    void create_validatesContentAndProduct() throws Exception {
        String questionId = JsonPath.read(mockMvc.perform(createRequest(productPid, "  색상이 사진과 같나요?  ")
                        .with(authHeaders.buyer(BUYER)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.questionId");

        assertThat(jdbc.queryForMap("SELECT content, status, buyer_id, answered_at FROM product_question WHERE public_id = ?", questionId))
                .containsEntry("content", "색상이 사진과 같나요?")
                .containsEntry("status", "VISIBLE")
                .containsEntry("buyer_id", BUYER)
                .containsEntry("answered_at", null);

        mockMvc.perform(createRequest(productPid, "   네글자임   ").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(createRequest(productPid, "가".repeat(501)).with(authHeaders.buyer(BUYER))).andExpect(status().isBadRequest());
        mockMvc.perform(createRequest(productPid, " " + "가".repeat(500) + " ").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isCreated());
        mockMvc.perform(createRequest(hiddenProductPid, "비노출 상품 질문").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("B4 수정(규칙 4): 미답변 + VISIBLE → 204 / 타인 404 / 답변 완료 422 / 숨김 422")
    void update_followsStateRules() throws Exception {
        mockMvc.perform(updateRequest(openPid, "배송은 며칠 걸리나요?").with(authHeaders.buyer(BUYER))).andExpect(status().isNoContent());
        assertThat(content(QUESTION_OPEN)).isEqualTo("배송은 며칠 걸리나요?");

        mockMvc.perform(updateRequest(openPid, "타인이 고친 질문").with(authHeaders.buyer(OTHER_BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_QUESTION_NOT_FOUND"));
        mockMvc.perform(updateRequest(answeredPid, "답변 뒤 수정 시도").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_QUESTION_INVALID_STATE"));
        mockMvc.perform(updateRequest(hiddenPid, "숨김 뒤 수정 시도").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_QUESTION_INVALID_STATE"));
        assertThat(content(QUESTION_ANSWERED)).isEqualTo("사이즈가 어떻게 되나요?");
        assertThat(content(QUESTION_HIDDEN)).isEqualTo("숨겨질 질문입니다");
    }

    @Test
    @DisplayName("B5 삭제(규칙 5): 타인 404 / 답변 완료 422 / 숨김 미답변 204(soft delete) / 이미 삭제 404")
    void delete_followsStateRules() throws Exception {
        mockMvc.perform(delete(BASE_URL + "/" + openPid).with(authHeaders.buyer(OTHER_BUYER))).andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE_URL + "/" + answeredPid).with(authHeaders.buyer(BUYER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_QUESTION_INVALID_STATE"));
        assertThat(deletedAt(QUESTION_OPEN)).isNull();
        assertThat(deletedAt(QUESTION_ANSWERED)).isNull();

        mockMvc.perform(delete(BASE_URL + "/" + hiddenPid).with(authHeaders.buyer(BUYER))).andExpect(status().isNoContent());
        assertThat(deletedAt(QUESTION_HIDDEN)).as("soft delete — 행은 남는다").isNotNull();
        mockMvc.perform(delete(BASE_URL + "/" + hiddenPid).with(authHeaders.buyer(BUYER))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("B6 내 질문: 본인 것만·숨김 포함·삭제 제외·최신순 · 숨김 사유 · editable/deletable · 작성자 식별 정보 없음")
    void listMine_includesHiddenAndFlags() throws Exception {
        fixture.insertQuestion(10831L, PRODUCT, OTHER_BUYER, "다른 사람 질문입니다", "VISIBLE", null, null, LocalDateTime.now());
        fixture.insertQuestion(10832L, PRODUCT, BUYER, "삭제된 내 질문입니다", "VISIBLE", null, null, LocalDateTime.now());
        fixture.markQuestionDeleted(10832L);

        mockMvc.perform(get(BASE_URL + "/me").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items[0].questionId").value(hiddenPid))
                .andExpect(jsonPath("$.items[0].status").value("HIDDEN"))
                .andExpect(jsonPath("$.items[0].hiddenReason").value(ProductQuestionFixture.HIDDEN_REASON))
                .andExpect(jsonPath("$.items[0].editable").value(false))
                .andExpect(jsonPath("$.items[0].deletable").value(true))
                .andExpect(jsonPath("$.items[0].productPublicId").value(productPid))
                .andExpect(jsonPath("$.items[1].questionId").value(answeredPid))
                .andExpect(jsonPath("$.items[1].answerContent").value("정사이즈입니다"))
                .andExpect(jsonPath("$.items[1].editable").value(false))
                .andExpect(jsonPath("$.items[1].deletable").value(false))
                .andExpect(jsonPath("$.items[2].questionId").value(openPid))
                .andExpect(jsonPath("$.items[2].editable").value(true))
                .andExpect(jsonPath("$.items[2].answerContent").doesNotExist())
                .andExpect(jsonPath("$.items[0].buyerId").doesNotExist());
    }

    // ---------- helpers ----------

    private MockHttpServletRequestBuilder createRequest(String productPublicId, String content) {
        return post(BASE_URL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"%s\",\"content\":\"%s\"}".formatted(productPublicId, content));
    }

    private MockHttpServletRequestBuilder updateRequest(String questionPublicId, String content) {
        return put(BASE_URL + "/" + questionPublicId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"%s\"}".formatted(content));
    }

    private int questionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_question WHERE product_id = ?", Integer.class, PRODUCT);
    }

    private String content(long questionId) {
        return jdbc.queryForObject("SELECT content FROM product_question WHERE id = ?", String.class, questionId);
    }

    private Object deletedAt(long questionId) {
        return jdbc.queryForMap("SELECT deleted_at FROM product_question WHERE id = ?", questionId).get("deleted_at");
    }
}
