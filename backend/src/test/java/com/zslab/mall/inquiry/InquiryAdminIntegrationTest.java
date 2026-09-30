package com.zslab.mall.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.common.security.JwtAuthenticationToken;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
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
 * 관리자 운영자 문의 통합 테스트(Track 106-4·실 MariaDB): 인가(구매자 차단)·목록 필터·마스킹·답변 등록·수정(미확인 복귀)·감사·대시보드 미답변 수.
 *
 * <p>관리자 목록·대시보드는 전역 집계라 건수 대신 이 대역 문의의 포함 여부와 DB 실측 대비 값을 본다.
 */
@AutoConfigureMockMvc
class InquiryAdminIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10920L;
    private static final long BAND_TO = 10939L;
    private static final long BUYER = 10921L;
    private static final long ADMIN = 10922L;
    private static final long ORDER = 10923L;
    private static final long INQUIRY_OLD = 10924L;
    private static final long INQUIRY_NEW = 10925L;
    private static final long INQUIRY_ANSWERED = 10926L;
    private static final String ADMIN_URL = "/api/v1/admin/inquiries";
    private static final String BUYER_URL = "/api/v1/inquiries";

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
    private String oldPid;
    private String newPid;
    private String answeredPid;

    @BeforeEach
    void setUp() {
        fixture = new InquiryFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(BUYER, "buyer10921@test.zslab");
        fixture.seedUser(ADMIN, "admin10922@test.zslab");
        orderPid = fixture.seedOrder(ORDER, BUYER);
        LocalDateTime base = LocalDateTime.now().minusDays(400);
        oldPid = fixture.insertInquiry(INQUIRY_OLD, BUYER, ORDER, "CLAIM", "반품 절차가 궁금해요", null, null, false, base);
        newPid = fixture.insertInquiry(INQUIRY_NEW, BUYER, null, "DELIVERY", "배송지를 바꾸고 싶어요", null, null, false, base.plusHours(1));
        answeredPid = fixture.insertInquiry(INQUIRY_ANSWERED, BUYER, null, "ACCOUNT", "탈퇴는 어떻게 하나요?", "마이페이지에서 가능합니다",
                ADMIN, true, base.plusHours(2));
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("A1 구매자: 구매자 쿠키 → 관리자 경로 401 · BUYER 인증 주입 → 403 / 관리자 쿠키 → 200(대조)")
    void buyer_cannotUseAdminEndpoints() throws Exception {
        mockMvc.perform(get(ADMIN_URL).with(authHeaders.buyer(BUYER))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ADMIN_URL).with(authentication(JwtAuthenticationToken.authenticated(BUYER, ActorRole.BUYER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(answerRequest(oldPid, "구매자가 단 답변").with(authHeaders.csrf())
                        .with(authentication(JwtAuthenticationToken.authenticated(BUYER, ActorRole.BUYER))))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForMap("SELECT answer_content FROM inquiry WHERE id = ?", INQUIRY_OLD)).containsEntry("answer_content", null);

        mockMvc.perform(get(ADMIN_URL).with(authHeaders.admin(ADMIN))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A2 목록: 기본 미답변·오래된 순 · 답변 완료 필터 · 카테고리 필터 · 작성자 이메일 마스킹 · 첨부 주문 · 카테고리 오값 400")
    void list_filtersAndMasks() throws Exception {
        String unanswered = mockMvc.perform(get(ADMIN_URL).param("size", "50").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.answeredAt)]").isEmpty())
                .andExpect(jsonPath("$.items[?(@.inquiryId == '%s')].buyerEmailMasked".formatted(oldPid)).value("bu***@test.zslab"))
                .andExpect(jsonPath("$.items[?(@.inquiryId == '%s')].orderId".formatted(oldPid)).value(orderPid))
                .andExpect(jsonPath("$.items[?(@.inquiryId == '%s')].orderNo".formatted(oldPid)).value("ORDIN" + ORDER))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(unanswered, "$.items[*].inquiryId");
        assertThat(ids).contains(oldPid, newPid).doesNotContain(answeredPid);
        assertThat(ids.indexOf(oldPid)).as("오래된 순").isLessThan(ids.indexOf(newPid));

        String answered = mockMvc.perform(get(ADMIN_URL).param("answered", "ANSWERED").param("size", "50").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.inquiryId == '%s')].answerContent".formatted(answeredPid)).value("마이페이지에서 가능합니다"))
                .andReturn().getResponse().getContentAsString();
        List<String> answeredIds = JsonPath.read(answered, "$.items[*].inquiryId");
        assertThat(answeredIds).contains(answeredPid).doesNotContain(oldPid, newPid);
        mockMvc.perform(get(ADMIN_URL).param("answered", "ALL").param("category", "CLAIM").param("size", "50")
                        .with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.category != 'CLAIM')]").isEmpty())
                .andExpect(jsonPath("$.items[?(@.inquiryId == '%s')]".formatted(oldPid)).isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.inquiryId == '%s')]".formatted(newPid)).isEmpty());
        mockMvc.perform(get(ADMIN_URL).param("category", "REVIEW_QUESTION").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A3 답변 등록 → 구매자 미확인 → 확인 → 답변 수정 시 미확인 복귀 · 같은 본문 재저장 무변경 · 감사 2건(답변 본문만·문의 본문 없음) · 없는 문의 404 · 공백 400")
    void answerAndModify_resetsUnreadAndAudits() throws Exception {
        mockMvc.perform(answerRequest(oldPid, "  반품은 주문 상세에서 신청하세요  ").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForMap("SELECT answer_content, answered_by, answer_checked_at FROM inquiry WHERE id = ?", INQUIRY_OLD))
                .containsEntry("answer_content", "반품은 주문 상세에서 신청하세요")
                .containsEntry("answered_by", ADMIN)
                .containsEntry("answer_checked_at", null);
        assertThat(myUnread(oldPid)).isTrue();

        mockMvc.perform(put(BUYER_URL + "/" + oldPid + "/answer-check").with(authHeaders.buyer(BUYER))).andExpect(status().isNoContent());
        assertThat(myUnread(oldPid)).isFalse();

        mockMvc.perform(answerRequest(oldPid, "주문 상세 > 반품 요청 버튼을 눌러 주세요").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForMap("SELECT answer_content, answer_checked_at FROM inquiry WHERE id = ?", INQUIRY_OLD))
                .containsEntry("answer_content", "주문 상세 > 반품 요청 버튼을 눌러 주세요")
                .containsEntry("answer_checked_at", null);
        assertThat(myUnread(oldPid)).as("수정된 답변은 다시 미확인").isTrue();

        mockMvc.perform(put(BUYER_URL + "/" + oldPid + "/answer-check").with(authHeaders.buyer(BUYER))).andExpect(status().isNoContent());
        mockMvc.perform(answerRequest(oldPid, " 주문 상세 > 반품 요청 버튼을 눌러 주세요 ").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isNoContent());
        assertThat(myUnread(oldPid)).as("같은 본문 재저장은 무변경 — 확인 상태 유지").isFalse();

        List<String> diffs = jdbc.queryForList(
                "SELECT diff_json FROM audit_log WHERE target_type = 'INQUIRY' AND target_id = ? AND action = 'UPDATE' AND actor_user_id = ? "
                        + "ORDER BY id", String.class, INQUIRY_OLD, ADMIN);
        assertThat(diffs).hasSize(2);
        assertThat(diffs.get(0)).contains("반품은 주문 상세에서 신청하세요").doesNotContain("반품 절차가 궁금해요");
        assertThat(diffs.get(1)).contains("반품은 주문 상세에서 신청하세요").contains("주문 상세 > 반품 요청 버튼을 눌러 주세요")
                .doesNotContain("반품 절차가 궁금해요");

        mockMvc.perform(answerRequest(InquiryFixture.pid("inq_", "NONE"), "없는 문의 답변").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
        mockMvc.perform(answerRequest(newPid, "   ").with(authHeaders.admin(ADMIN))).andExpect(status().isBadRequest());
        mockMvc.perform(answerRequest(newPid, "가".repeat(1001)).with(authHeaders.admin(ADMIN))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForMap("SELECT answer_content FROM inquiry WHERE id = ?", INQUIRY_NEW)).containsEntry("answer_content", null);
    }

    @Test
    @DisplayName("A4 대시보드 미답변 수 = DB 미답변(삭제 제외) 실측 · 답변 1건 → 1 감소 · 삭제 문의 제외")
    void dashboard_countsUnansweredInquiries() throws Exception {
        fixture.insertInquiry(10927L, BUYER, null, "OTHER", "삭제된 미답변 문의", null, null, false, LocalDateTime.now());
        fixture.markInquiryDeleted(10927L);

        long before = dashboardInquiryUnanswered();
        assertThat(before).isEqualTo(unansweredInDatabase());

        mockMvc.perform(answerRequest(newPid, "배송 전이라 변경 가능합니다").with(authHeaders.admin(ADMIN))).andExpect(status().isNoContent());
        assertThat(dashboardInquiryUnanswered()).isEqualTo(before - 1);
    }

    // ---------- helpers ----------

    private MockHttpServletRequestBuilder answerRequest(String inquiryPublicId, String content) {
        return put(ADMIN_URL + "/" + inquiryPublicId + "/answer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"%s\"}".formatted(content));
    }

    private boolean myUnread(String inquiryPublicId) throws Exception {
        String body = mockMvc.perform(get(BUYER_URL + "/me").param("size", "50").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Boolean> unread = JsonPath.read(body, "$.items[?(@.inquiryId == '%s')].unread".formatted(inquiryPublicId));
        assertThat(unread).hasSize(1);
        return unread.get(0);
    }

    private long dashboardInquiryUnanswered() throws Exception {
        String body = mockMvc.perform(get("/api/v1/admin/dashboard").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(body, "$.pending.inquiryUnanswered").longValue();
    }

    private long unansweredInDatabase() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inquiry WHERE answered_at IS NULL AND deleted_at IS NULL", Long.class);
    }
}
