package com.zslab.mall.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 구매자 운영자 문의 통합 테스트(Track 106-4·실 MariaDB): 인가(비로그인 401·셀러·관리자 차단)·주문 첨부 소유·수정·삭제·확인 상태 규칙·내 문의·V43 CHECK.
 *
 * <p>셀러·관리자 차단: 역할 쿠키는 경로 접두사에 맞는 것만 읽으므로(D-235) 셀러·관리자 쿠키로 구매자 경로를 부르면 익명과 같은 401이다. 이것만으로는
 * {@code /api/v1/inquiries/**} hasRole BUYER 매처를 검증하지 못하므로 SELLER·ADMIN 인증을 직접 주입해 403을 확인한다 — 매처가 없으면 주입된
 * id가 구매자로 해석돼 문의가 만들어진다. 같은 요청을 구매자 쿠키로 보내 201이 되는 것으로 엔드포인트 부재 false-green을 막는다.
 */
@AutoConfigureMockMvc
class InquiryBuyerIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10900L;
    private static final long BAND_TO = 10919L;
    private static final long BUYER = 10901L;
    private static final long OTHER_BUYER = 10902L;
    private static final long ADMIN = 10903L;
    private static final long SELLER_USER = 10904L;
    private static final long ORDER = 10905L;
    private static final long OTHER_ORDER = 10906L;
    private static final long INQUIRY_OPEN = 10907L;
    private static final long INQUIRY_ANSWERED = 10908L;
    private static final long INQUIRY_CHECKED = 10909L;
    private static final String BASE_URL = "/api/v1/inquiries";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private InquiryFixture fixture;
    private String orderPid;
    private String otherOrderPid;
    private String openPid;
    private String answeredPid;
    private String checkedPid;

    @BeforeEach
    void setUp() {
        fixture = new InquiryFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(BUYER, "buyer10901@test.zslab");
        fixture.seedUser(OTHER_BUYER, "buyer10902@test.zslab");
        fixture.seedUser(ADMIN, "admin10903@test.zslab");
        orderPid = fixture.seedOrder(ORDER, BUYER);
        otherOrderPid = fixture.seedOrder(OTHER_ORDER, OTHER_BUYER);
        LocalDateTime base = LocalDateTime.now().minusHours(3);
        openPid = fixture.insertInquiry(INQUIRY_OPEN, BUYER, ORDER, "DELIVERY", "배송이 언제 시작되나요?", null, null, false, base);
        answeredPid = fixture.insertInquiry(INQUIRY_ANSWERED, BUYER, null, "ACCOUNT", "비밀번호를 잊었어요", "재설정 안내드립니다", ADMIN,
                false, base.plusHours(1));
        checkedPid = fixture.insertInquiry(INQUIRY_CHECKED, BUYER, null, "OTHER", "기타 문의입니다", "확인된 답변입니다", ADMIN, true,
                base.plusHours(2));
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("I1 비로그인 → 등록·수정·삭제·확인·내 문의 401")
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(createRequest("DELIVERY", "익명 문의입니다", null).with(authHeaders.csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(updateRequest(openPid, "DELIVERY", "익명 수정입니다").with(authHeaders.csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BASE_URL + "/" + openPid).with(authHeaders.csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(put(BASE_URL + "/" + answeredPid + "/answer-check").with(authHeaders.csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE_URL + "/me")).andExpect(status().isUnauthorized());
        assertThat(inquiryCount()).isEqualTo(3);
        assertThat(checkedAt(INQUIRY_ANSWERED)).isNull();
    }

    @Test
    @DisplayName("I2 셀러·관리자: 역할 쿠키 → 익명 401 · 인증 주입 → 403(hasRole BUYER) / 같은 등록을 구매자 쿠키로 → 201(대조)")
    void sellerAndAdmin_cannotActAsBuyer() throws Exception {
        mockMvc.perform(createRequest("OTHER", "셀러 쿠키 문의", null).with(authHeaders.seller(SELLER_USER))).andExpect(status().isUnauthorized());
        mockMvc.perform(createRequest("OTHER", "관리자 쿠키 문의", null).with(authHeaders.admin(ADMIN))).andExpect(status().isUnauthorized());

        mockMvc.perform(createRequest("OTHER", "셀러 인증 문의", null).with(authHeaders.csrf()).with(as(SELLER_USER, ActorRole.SELLER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(createRequest("OTHER", "관리자 인증 문의", null).with(authHeaders.csrf()).with(as(ADMIN, ActorRole.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(updateRequest(openPid, "OTHER", "셀러 인증 수정").with(authHeaders.csrf()).with(as(SELLER_USER, ActorRole.SELLER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE_URL + "/" + openPid).with(authHeaders.csrf()).with(as(ADMIN, ActorRole.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(BASE_URL + "/" + answeredPid + "/answer-check").with(authHeaders.csrf()).with(as(ADMIN, ActorRole.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE_URL + "/me").with(as(SELLER_USER, ActorRole.SELLER))).andExpect(status().isForbidden());
        assertThat(inquiryCount()).isEqualTo(3);
        assertThat(checkedAt(INQUIRY_ANSWERED)).isNull();

        mockMvc.perform(createRequest("OTHER", "구매자 쿠키 문의", null).with(authHeaders.buyer(BUYER))).andExpect(status().isCreated());
        assertThat(inquiryCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("I3 등록: 성공(trim·카테고리·본인 주문 첨부) · 주문 없이 성공 / 타인 주문·없는 주문 404 / 빈 주문 번호·카테고리 오값·본문 4자·501자 400")
    void create_validatesOrderOwnershipAndFormat() throws Exception {
        String inquiryId = JsonPath.read(mockMvc.perform(createRequest("CLAIM", "  반품하고 싶어요  ", orderPid).with(authHeaders.buyer(BUYER)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.inquiryId");
        assertThat(jdbc.queryForMap("SELECT content, category, buyer_id, order_id, answered_at FROM inquiry WHERE public_id = ?", inquiryId))
                .containsEntry("content", "반품하고 싶어요")
                .containsEntry("category", "CLAIM")
                .containsEntry("buyer_id", BUYER)
                .containsEntry("order_id", ORDER)
                .containsEntry("answered_at", null);

        String withoutOrder = JsonPath.read(mockMvc.perform(createRequest("ORDER_PAYMENT", "결제 수단 문의입니다", null)
                        .with(authHeaders.buyer(BUYER)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.inquiryId");
        assertThat(jdbc.queryForMap("SELECT order_id FROM inquiry WHERE public_id = ?", withoutOrder)).containsEntry("order_id", null);

        int countBeforeRejects = inquiryCount();
        mockMvc.perform(createRequest("CLAIM", "남의 주문 문의", otherOrderPid).with(authHeaders.buyer(BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(createRequest("CLAIM", "없는 주문 문의", InquiryFixture.pid("ord_", "NONE")).with(authHeaders.buyer(BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(createRequest("CLAIM", "빈 주문 번호 문의", "").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(createRequest("REVIEW_QUESTION", "없는 카테고리 문의", null).with(authHeaders.buyer(BUYER)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(createRequest("OTHER", "   네글자임   ", null).with(authHeaders.buyer(BUYER))).andExpect(status().isBadRequest());
        mockMvc.perform(createRequest("OTHER", "가".repeat(501), null).with(authHeaders.buyer(BUYER))).andExpect(status().isBadRequest());
        assertThat(inquiryCount()).isEqualTo(countBeforeRejects);
    }

    @Test
    @DisplayName("I4 수정: 미답변 → 204(카테고리·본문 교체·주문 유지) / 타인 404 / 답변 완료 422")
    void update_followsStateRules() throws Exception {
        mockMvc.perform(updateRequest(openPid, "CLAIM", "교환도 가능한가요?").with(authHeaders.buyer(BUYER))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForMap("SELECT category, content, order_id FROM inquiry WHERE id = ?", INQUIRY_OPEN))
                .containsEntry("category", "CLAIM")
                .containsEntry("content", "교환도 가능한가요?")
                .containsEntry("order_id", ORDER);

        mockMvc.perform(updateRequest(openPid, "OTHER", "타인이 고친 문의").with(authHeaders.buyer(OTHER_BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
        mockMvc.perform(updateRequest(answeredPid, "OTHER", "답변 뒤 수정 시도").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INQUIRY_INVALID_STATE"));
        assertThat(jdbc.queryForObject("SELECT content FROM inquiry WHERE id = ?", String.class, INQUIRY_ANSWERED))
                .isEqualTo("비밀번호를 잊었어요");
    }

    @Test
    @DisplayName("I5 삭제: 타인 404 / 답변 완료 422 / 미답변 204(soft delete) / 이미 삭제 → 삭제·수정·확인 404")
    void delete_followsStateRules() throws Exception {
        mockMvc.perform(delete(BASE_URL + "/" + openPid).with(authHeaders.buyer(OTHER_BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
        mockMvc.perform(delete(BASE_URL + "/" + answeredPid).with(authHeaders.buyer(BUYER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INQUIRY_INVALID_STATE"));
        assertThat(deletedAt(INQUIRY_OPEN)).isNull();
        assertThat(deletedAt(INQUIRY_ANSWERED)).isNull();

        mockMvc.perform(delete(BASE_URL + "/" + openPid).with(authHeaders.buyer(BUYER))).andExpect(status().isNoContent());
        assertThat(deletedAt(INQUIRY_OPEN)).as("soft delete — 행은 남는다").isNotNull();
        mockMvc.perform(delete(BASE_URL + "/" + openPid).with(authHeaders.buyer(BUYER))).andExpect(status().isNotFound());
        mockMvc.perform(updateRequest(openPid, "OTHER", "삭제 뒤 수정 시도").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));

        fixture.markInquiryDeleted(INQUIRY_ANSWERED);
        mockMvc.perform(put(BASE_URL + "/" + answeredPid + "/answer-check").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
        assertThat(checkedAt(INQUIRY_ANSWERED)).isNull();
    }

    @Test
    @DisplayName("I6 답변 확인: 타인 404 / 미답변 422 / 미확인 → 204·시각 기록 / 다시 확인 204·시각 불변(멱등)")
    void checkAnswer_isOwnerOnlyAndIdempotent() throws Exception {
        mockMvc.perform(put(BASE_URL + "/" + answeredPid + "/answer-check").with(authHeaders.buyer(OTHER_BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
        assertThat(checkedAt(INQUIRY_ANSWERED)).isNull();
        mockMvc.perform(put(BASE_URL + "/" + openPid + "/answer-check").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INQUIRY_INVALID_STATE"));

        mockMvc.perform(put(BASE_URL + "/" + answeredPid + "/answer-check").with(authHeaders.buyer(BUYER))).andExpect(status().isNoContent());
        Object firstCheckedAt = checkedAt(INQUIRY_ANSWERED);
        assertThat(firstCheckedAt).isNotNull();
        mockMvc.perform(put(BASE_URL + "/" + answeredPid + "/answer-check").with(authHeaders.buyer(BUYER))).andExpect(status().isNoContent());
        assertThat(checkedAt(INQUIRY_ANSWERED)).isEqualTo(firstCheckedAt);
    }

    @Test
    @DisplayName("I7 내 문의: 본인 것만·삭제 제외·최신순 · 주문 publicId·주문번호 · editable/deletable/unread · 작성자 식별 정보 없음")
    void listMine_returnsFlagsAndOrder() throws Exception {
        fixture.insertInquiry(10910L, OTHER_BUYER, null, "OTHER", "다른 사람 문의입니다", null, null, false, LocalDateTime.now());
        fixture.insertInquiry(10911L, BUYER, null, "OTHER", "삭제된 내 문의입니다", null, null, false, LocalDateTime.now());
        fixture.markInquiryDeleted(10911L);

        mockMvc.perform(get(BASE_URL + "/me").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items[0].inquiryId").value(checkedPid))
                .andExpect(jsonPath("$.items[0].unread").value(false))
                .andExpect(jsonPath("$.items[0].editable").value(false))
                .andExpect(jsonPath("$.items[0].orderId").doesNotExist())
                .andExpect(jsonPath("$.items[1].inquiryId").value(answeredPid))
                .andExpect(jsonPath("$.items[1].answerContent").value("재설정 안내드립니다"))
                .andExpect(jsonPath("$.items[1].unread").value(true))
                .andExpect(jsonPath("$.items[1].deletable").value(false))
                .andExpect(jsonPath("$.items[2].inquiryId").value(openPid))
                .andExpect(jsonPath("$.items[2].category").value("DELIVERY"))
                .andExpect(jsonPath("$.items[2].orderId").value(orderPid))
                .andExpect(jsonPath("$.items[2].orderNo").value("ORDIN" + ORDER))
                .andExpect(jsonPath("$.items[2].editable").value(true))
                .andExpect(jsonPath("$.items[2].deletable").value(true))
                .andExpect(jsonPath("$.items[2].unread").value(false))
                .andExpect(jsonPath("$.items[2].answerContent").doesNotExist())
                .andExpect(jsonPath("$.items[0].buyerId").doesNotExist());
    }

    @Test
    @DisplayName("D1 V43 CHECK: 카테고리 외 값·답변 3컬럼 일부만·답변 없는 확인 시각 → 거부 / 셋 다 채운 답변은 통과(대조)")
    void schemaChecks_rejectInvalidRows() {
        assertThatThrownBy(() -> jdbc.update("UPDATE inquiry SET category = 'REVIEW_QUESTION' WHERE id = ?", INQUIRY_OPEN))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_inquiry_category");
        assertThatThrownBy(() -> jdbc.update("UPDATE inquiry SET answer_content = '부분 답변' WHERE id = ?", INQUIRY_OPEN))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_inquiry_answer");
        assertThatThrownBy(() -> jdbc.update("UPDATE inquiry SET answer_checked_at = NOW(6) WHERE id = ?", INQUIRY_OPEN))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_inquiry_answer_checked");
        Map<String, Object> row = jdbc.queryForMap("SELECT category, answer_content, answer_checked_at FROM inquiry WHERE id = ?", INQUIRY_OPEN);
        assertThat(row).containsEntry("category", "DELIVERY").containsEntry("answer_content", null).containsEntry("answer_checked_at", null);

        assertThat(jdbc.update("UPDATE inquiry SET answer_content = '전체 답변', answered_at = NOW(6), answered_by = ? WHERE id = ?",
                ADMIN, INQUIRY_OPEN)).isEqualTo(1);
    }

    // ---------- helpers ----------

    private static RequestPostProcessor as(long userId, ActorRole role) {
        return authentication(JwtAuthenticationToken.authenticated(userId, role));
    }

    private MockHttpServletRequestBuilder createRequest(String category, String content, String orderPublicId) {
        String orderField = orderPublicId == null ? "" : ",\"orderId\":\"%s\"".formatted(orderPublicId);
        return post(BASE_URL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"%s\",\"content\":\"%s\"%s}".formatted(category, content, orderField));
    }

    private MockHttpServletRequestBuilder updateRequest(String inquiryPublicId, String category, String content) {
        return put(BASE_URL + "/" + inquiryPublicId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"%s\",\"content\":\"%s\"}".formatted(category, content));
    }

    private int inquiryCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inquiry WHERE buyer_id BETWEEN ? AND ?", Integer.class, BAND_FROM, BAND_TO);
    }

    private Object checkedAt(long inquiryId) {
        return jdbc.queryForMap("SELECT answer_checked_at FROM inquiry WHERE id = ?", inquiryId).get("answer_checked_at");
    }

    private Object deletedAt(long inquiryId) {
        return jdbc.queryForMap("SELECT deleted_at FROM inquiry WHERE id = ?", inquiryId).get("deleted_at");
    }
}
