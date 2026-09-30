package com.zslab.mall.demoseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.demoseed.service.DemoQuestionQuota;
import com.zslab.mall.demoseed.template.DemoProductQuestionTemplates;
import com.zslab.mall.demoseed.template.DemoProductQuestionTemplates.QuestionAnswer;
import com.zslab.mall.productquestion.service.SellerProductQuestionService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 데모 상품 Q&A 적재 관리자 API 통합 테스트(D-244 · 실 MariaDB): 권한(SUPER_ADMIN 전용) · dryRun 쓰기 0 · 목표 충족(공개 10 · 미답변 1~5 ·
 * 답변자 = 소유 구성원) · 재호출 추가 0 · 제외 규칙 · 시각 불변식 · 상품 단위 실패 격리 · 실행당 감사 1건.
 *
 * <p>시더는 DB 전체의 '데모 %' 셀러를 대상으로 하므로 대역 밖 데모 셀러가 있으면 결과가 섞인다 — setUp에서 0건을 먼저 단언한다.
 */
@AutoConfigureMockMvc
class DemoSeedProductQuestionIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 12400L;
    private static final long BAND_TO = 12499L;
    private static final long SUPER_ADMIN = 12401L;
    private static final long OPERATOR = 12402L;
    private static final long BUYER_A = 12403L;
    private static final long BUYER_B = 12404L;
    private static final long BUYER_C = 12405L;
    private static final long WITHDRAWN_BUYER = 12406L;
    private static final long OWNER = 12407L;
    private static final long NON_DEMO_BUYER = 12408L;
    private static final long SUSPENDED_OWNER = 12409L;
    private static final long PLAIN_OWNER = 12414L;
    private static final long DEMO_SELLER = 12410L;
    private static final long NO_OWNER_SELLER = 12411L;
    private static final long SUSPENDED_SELLER = 12412L;
    private static final long PLAIN_SELLER = 12413L;
    private static final long P_EMPTY = 12420L;
    private static final long P_FULL = 12421L;
    private static final long P_NO_UNANSWERED = 12422L;
    private static final long P_MANY_UNANSWERED = 12423L;
    private static final long P_STOPPED = 12424L;
    private static final long P_FAIL = 12425L;
    private static final long P_NO_OWNER = 12426L;
    private static final long P_SUSPENDED = 12427L;
    private static final long P_PLAIN = 12428L;
    private static final long FIXED_QUESTION_FROM = 12430L;
    /** 픽스처가 직접 넣은 질문의 public_id 접두사 — 시더가 만든 행(ULID)과 구분한다(자동 증가 id는 실행 순서에 따라 대역 안에 들어올 수 있다). */
    private static final String FIXTURE_QUESTION_PID = "pqn_DSQ%";
    private static final String URL = "/api/v1/admin/demo-seed/product-questions";
    private static final String DEMO_DOMAIN = "@demo.zslab-mall.com";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    /** 상품 단위 롤백 검증용 — 기본은 실제 동작, 롤백 테스트에서만 N번째 답변에 예외를 심는다(테스트마다 초기화). */
    @MockitoSpyBean
    private SellerProductQuestionService sellerProductQuestionService;

    private DemoSeedFixture fixture;
    private LocalDateTime userCreatedAt;
    private LocalDateTime productCreatedAt;

    @BeforeEach
    void setUp() {
        fixture = new DemoSeedFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller WHERE company_name LIKE '데모 %' AND id NOT BETWEEN ? AND ?",
                Long.class, BAND_FROM, BAND_TO)).as("대역 밖 데모 셀러가 있으면 시더 대상이 섞인다").isZero();

        userCreatedAt = LocalDateTime.now().minusDays(30);
        productCreatedAt = LocalDateTime.now().minusDays(20);
        fixture.seedUser(SUPER_ADMIN, "dsq-super@example.com", "SUPER_ADMIN", userCreatedAt, false);
        fixture.seedUser(OPERATOR, "dsq-operator@example.com", "ADMIN_OPERATOR", userCreatedAt, false);
        fixture.seedUser(BUYER_A, "dsq-buyer-a" + DEMO_DOMAIN, "BUYER", userCreatedAt, false);
        fixture.seedUser(BUYER_B, "dsq-buyer-b" + DEMO_DOMAIN, "BUYER", userCreatedAt, false);
        // 작성자 하한 검증용: 상품보다 늦게 가입한 구매자
        fixture.seedUser(BUYER_C, "dsq-buyer-c" + DEMO_DOMAIN, "BUYER", LocalDateTime.now().minusDays(5), false);
        fixture.seedUser(WITHDRAWN_BUYER, "dsq-withdrawn" + DEMO_DOMAIN, "BUYER", userCreatedAt, true);
        fixture.seedUser(OWNER, "dsq-owner" + DEMO_DOMAIN, "BUYER", userCreatedAt, false);
        fixture.seedUser(NON_DEMO_BUYER, "dsq-plain@example.com", "BUYER", userCreatedAt, false);
        fixture.seedUser(SUSPENDED_OWNER, "dsq-suspended-owner@example.com", "BUYER", userCreatedAt, false);
        fixture.seedUser(PLAIN_OWNER, "dsq-plain-owner@example.com", "BUYER", userCreatedAt, false);

        fixture.seedSeller(DEMO_SELLER, "데모 시더샵", "ACTIVE");
        fixture.seedOwner(OWNER, DEMO_SELLER);
        fixture.seedSeller(NO_OWNER_SELLER, "데모 무구성원", "ACTIVE");
        fixture.seedSeller(SUSPENDED_SELLER, "데모 정지샵", "SUSPENDED");
        fixture.seedOwner(SUSPENDED_OWNER, SUSPENDED_SELLER);
        fixture.seedSeller(PLAIN_SELLER, "일반샵", "ACTIVE");
        fixture.seedOwner(PLAIN_OWNER, PLAIN_SELLER);

        for (long product : List.of(P_EMPTY, P_FULL, P_NO_UNANSWERED, P_MANY_UNANSWERED, P_FAIL)) {
            fixture.seedProduct(product, DEMO_SELLER, "SALE", productCreatedAt);
        }
        fixture.seedProduct(P_STOPPED, DEMO_SELLER, "STOPPED", productCreatedAt);
        fixture.seedProduct(P_NO_OWNER, NO_OWNER_SELLER, "SALE", productCreatedAt);
        fixture.seedProduct(P_SUSPENDED, SUSPENDED_SELLER, "SALE", productCreatedAt);
        fixture.seedProduct(P_PLAIN, PLAIN_SELLER, "SALE", productCreatedAt);

        LocalDateTime asked = productCreatedAt.plusDays(1);
        long questionId = FIXED_QUESTION_FROM;
        for (int index = 0; index < 8; index++) {   // P_FULL: 공개 11(답변 8 + 미답변 3) → 추가 0
            fixture.insertQuestion(questionId++, P_FULL, BUYER_A, "기존 답변 질문 " + index, "VISIBLE", "기존 답변", OWNER, asked);
        }
        for (int index = 0; index < 3; index++) {
            fixture.insertQuestion(questionId++, P_FULL, BUYER_A, "기존 미답변 질문 " + index, "VISIBLE", null, null, asked);
        }
        for (int index = 0; index < 12; index++) {  // P_NO_UNANSWERED: 공개 12 · 미답변 0 → 미답변 1 추가
            fixture.insertQuestion(questionId++, P_NO_UNANSWERED, BUYER_A, "답변만 있는 질문 " + index, "VISIBLE", "기존 답변", OWNER, asked);
        }
        for (int index = 0; index < 7; index++) {   // P_MANY_UNANSWERED: 공개 7 · 미답변 7(목표 초과 유지) → 답변 질문 3 추가
            fixture.insertQuestion(questionId++, P_MANY_UNANSWERED, BUYER_A, "미답변 많은 질문 " + index, "VISIBLE", null, null, asked);
        }
        // P_FAIL: 대체 세트 문구를 전부 숨김 질문으로 선점 → 공개 0이라 10건 필요하지만 쓸 문구 0 → 상품 롤백
        for (QuestionAnswer template : DemoProductQuestionTemplates.forCategory(DemoProductQuestionTemplates.FALLBACK_CATEGORY)) {
            fixture.insertQuestion(questionId++, P_FAIL, BUYER_A, template.question(), "HIDDEN", null, null, asked);
        }
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("권한: 비인증 401 · 구매자 쿠키 401(역할 쿠키 경로 규칙) · ADMIN_OPERATOR 403 / SUPER_ADMIN 200 — 거부 경로는 쓰기 0")
    void onlySuperAdminCanSeed() throws Exception {
        mockMvc.perform(post(URL).param("dryRun", "false")).andExpect(status().isUnauthorized());
        mockMvc.perform(request(false).with(authHeaders.buyer(BUYER_A))).andExpect(status().isUnauthorized());
        mockMvc.perform(request(false).with(authHeaders.admin(OPERATOR))).andExpect(status().isForbidden());
        assertThat(questionCount(P_EMPTY)).isZero();
        assertThat(auditCount()).isZero();

        mockMvc.perform(request(true).with(authHeaders.admin(SUPER_ADMIN))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("dryRun(기본값): 계획만 응답 · 질문·감사 쓰기 0")
    void dryRun_writesNothing() throws Exception {
        long before = bandQuestionCount();
        mockMvc.perform(post(URL).with(authHeaders.admin(SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.createdQuestionCount").value(0))
                .andExpect(jsonPath("$.products[?(@.productPublicId == '%s')].addQuestionCount", pid(P_EMPTY)).value(10));
        assertThat(bandQuestionCount()).isEqualTo(before);
        assertThat(auditCount()).isZero();
    }

    @Test
    @DisplayName("실행: 공개 10 이상 · 미답변 1~5(목표 초과 기존분 유지) · 답변자 = 소유 구성원 · 제외 셀러·비대상 상품 무변경 · 실패 상품 롤백 · 감사 1건")
    void execute_fillsShortageOnly() throws Exception {
        int unansweredTarget = DemoQuestionQuota.unansweredTarget(P_EMPTY);
        mockMvc.perform(request(false).with(authHeaders.admin(SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetSellerCount").value(1))
                .andExpect(jsonPath("$.targetProductCount").value(5))
                .andExpect(jsonPath("$.createdQuestionCount").value(10 + 1 + 3))
                .andExpect(jsonPath("$.createdAnswerCount").value(10 - unansweredTarget + 3))
                .andExpect(jsonPath("$.excludedSellers.length()").value(2))
                .andExpect(jsonPath("$.excludedSellers[?(@.companyName == '데모 무구성원')].reason").value("소유 구성원 없음"))
                .andExpect(jsonPath("$.excludedSellers[?(@.companyName == '데모 정지샵')].reason").value("셀러 상태가 ACTIVE가 아님"))
                .andExpect(jsonPath("$.failedProducts.length()").value(1))
                .andExpect(jsonPath("$.failedProducts[0].productPublicId").value(pid(P_FAIL)));

        assertThat(publicCount(P_EMPTY)).isEqualTo(10);
        assertThat(publicUnanswered(P_EMPTY)).isEqualTo(unansweredTarget).isBetween(1L, 5L);
        assertThat(publicCount(P_FULL)).isEqualTo(11);
        assertThat(publicCount(P_NO_UNANSWERED)).isEqualTo(13);
        assertThat(publicUnanswered(P_NO_UNANSWERED)).isEqualTo(1);
        assertThat(publicCount(P_MANY_UNANSWERED)).isEqualTo(10);
        assertThat(publicUnanswered(P_MANY_UNANSWERED)).isEqualTo(7);
        assertThat(questionCount(P_FAIL)).isEqualTo(12);   // 롤백 — 숨김 12건만
        for (long untouched : List.of(P_STOPPED, P_NO_OWNER, P_SUSPENDED, P_PLAIN)) {
            assertThat(questionCount(untouched)).as("비대상 상품 %d", untouched).isZero();
        }
        assertThat(jdbc.queryForList("SELECT DISTINCT answered_by FROM product_question WHERE product_id BETWEEN ? AND ? "
                + "AND public_id NOT LIKE ? AND answered_by IS NOT NULL", Long.class, BAND_FROM, BAND_TO, FIXTURE_QUESTION_PID))
                .containsExactly(OWNER);
        List<String> authorEmails = jdbc.queryForList("SELECT DISTINCT u.email FROM product_question q JOIN `user` u ON u.id = q.buyer_id "
                + "WHERE q.product_id BETWEEN ? AND ? AND q.public_id NOT LIKE ?", String.class, BAND_FROM, BAND_TO, FIXTURE_QUESTION_PID);
        assertThat(authorEmails).isNotEmpty().allMatch(email -> email.endsWith(DEMO_DOMAIN))
                .doesNotContain("dsq-withdrawn" + DEMO_DOMAIN, "dsq-owner" + DEMO_DOMAIN);
        assertThat(jdbc.queryForList("SELECT content FROM product_question WHERE product_id = ?", String.class, P_EMPTY))
                .doesNotHaveDuplicates();
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("상품 단위 롤백: 질문 생성 도중 답변 단계 예외 → 그 상품은 이번 호출분 0건 · 다른 상품은 정상 적재 · 실패 상품에 표시")
    void midWriteFailure_rollsBackOnlyThatProduct() throws Exception {
        // 상품은 id 순으로 처리된다 — P_EMPTY(첫 대상)가 질문·답변을 번갈아 만들다 3번째 답변에서 실패한다(질문 3건 생성 후)
        AtomicInteger answerCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (answerCalls.incrementAndGet() == 3) {
                throw new IllegalStateException("테스트 주입 실패");
            }
            return invocation.callRealMethod();
        }).when(sellerProductQuestionService).answer(any(), any(), any(), any());

        String body = mockMvc.perform(request(false).with(authHeaders.admin(SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 롤백 단언을 응답 단언보다 먼저 둔다 — 트랜잭션 경계가 없으면 여기서 "이미 만든 질문이 남음"으로 깨진다
        assertThat(questionCount(P_EMPTY)).as("실패 상품의 이번 호출분 질문·답변은 롤백").isZero();
        assertThat(answerCalls.get()).as("예외 주입 시점에 P_EMPTY 질문이 이미 만들어졌어야 롤백을 검증한다").isGreaterThanOrEqualTo(3);
        assertThat(publicCount(P_NO_UNANSWERED)).isEqualTo(13);
        assertThat(publicCount(P_MANY_UNANSWERED)).isEqualTo(10);
        assertThat(JsonPath.<Integer>read(body, "$.createdQuestionCount")).isEqualTo(1 + 3);
        assertThat(JsonPath.<List<String>>read(body, "$.failedProducts[*].productPublicId")).containsExactlyInAnyOrder(pid(P_EMPTY), pid(P_FAIL));
        assertThat(JsonPath.<List<String>>read(body, "$.failedProducts[?(@.productPublicId == '" + pid(P_EMPTY) + "')].reason"))
                .containsExactly("IllegalStateException: 테스트 주입 실패");
    }

    @Test
    @DisplayName("재호출: 추가 0(실패 상품은 그대로 실패) · 실행마다 감사 1건")
    void recall_addsNothing() throws Exception {
        mockMvc.perform(request(false).with(authHeaders.admin(SUPER_ADMIN))).andExpect(status().isOk());
        long after = bandQuestionCount();
        mockMvc.perform(request(false).with(authHeaders.admin(SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdQuestionCount").value(0))
                .andExpect(jsonPath("$.createdAnswerCount").value(0));
        assertThat(bandQuestionCount()).isEqualTo(after);
        assertThat(auditCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("시각 불변식: 질문 ≥ 상품·작성자 생성 · 답변 > 질문 · 모두 현재 이전 · updated_at = 마지막 변경 시각")
    void timestamps_areShiftedIntoPast() throws Exception {
        mockMvc.perform(request(false).with(authHeaders.admin(SUPER_ADMIN))).andExpect(status().isOk());
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT q.created_at, q.updated_at, q.answered_at, u.created_at AS author_created_at "
                + "FROM product_question q JOIN `user` u ON u.id = q.buyer_id WHERE q.product_id BETWEEN ? AND ? AND q.public_id NOT LIKE ?",
                BAND_FROM, BAND_TO, FIXTURE_QUESTION_PID);
        assertThat(rows).hasSize(10 + 1 + 3);
        for (Map<String, Object> row : rows) {
            LocalDateTime created = time(row.get("created_at"));
            LocalDateTime answered = time(row.get("answered_at"));
            assertThat(created).isAfterOrEqualTo(productCreatedAt).isAfterOrEqualTo(time(row.get("author_created_at"))).isBefore(now);
            if (answered == null) {
                assertThat(time(row.get("updated_at"))).isEqualTo(created);
            } else {
                assertThat(answered).isAfter(created).isBefore(now);
                assertThat(time(row.get("updated_at"))).isEqualTo(answered);
            }
        }
    }

    private MockHttpServletRequestBuilder request(boolean dryRun) {
        return post(URL).param("dryRun", String.valueOf(dryRun));
    }

    private static String pid(long productId) {
        return DemoSeedFixture.pid("prd_", "DSP" + productId);
    }

    private long questionCount(long productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_question WHERE product_id = ? AND deleted_at IS NULL", Long.class, productId);
    }

    private long publicCount(long productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_question WHERE product_id = ? AND status = 'VISIBLE' AND deleted_at IS NULL",
                Long.class, productId);
    }

    private long publicUnanswered(long productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_question WHERE product_id = ? AND status = 'VISIBLE' AND deleted_at IS NULL "
                + "AND answered_at IS NULL", Long.class, productId);
    }

    private long bandQuestionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_question WHERE product_id BETWEEN ? AND ?", Long.class, BAND_FROM, BAND_TO);
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'DEMO_SEED' AND actor_user_id = ?", Long.class,
                SUPER_ADMIN);
    }

    private static LocalDateTime time(Object value) {
        return value == null ? null : ((Timestamp) value).toLocalDateTime();
    }
}
