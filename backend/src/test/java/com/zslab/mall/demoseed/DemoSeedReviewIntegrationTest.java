package com.zslab.mall.demoseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.demoseed.repository.DemoSeedRepository;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 데모 리뷰 적재 관리자 API 통합 테스트(D-245 · 실 MariaDB): 권한 · dryRun 쓰기 0 · 목표 도달(공개 110) · 재호출 0 · 작성자 = 품목 구매자 ·
 * 제외(비데모 셀러 상품·리뷰 이력 품목·미확정·비데모·탈퇴 구매자) · 시각 불변식 · 품목 단위 롤백 · 실행당 감사 1건.
 *
 * <p>목표(110)는 DB 전체의 데모 구매자 공개 리뷰 수로 판정하므로, 대역 구매자에게 공개 리뷰 106건을 먼저 넣어 부족분을 4건으로 만든다. 대역 밖
 * 데모 구매자 공개 리뷰나 데모 셀러가 있으면 결과가 섞이므로 setUp에서 0건을 먼저 단언한다.
 */
@AutoConfigureMockMvc
class DemoSeedReviewIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 12500L;
    private static final long BAND_TO = 12799L;
    private static final long SUPER_ADMIN = 12501L;
    private static final long OPERATOR = 12502L;
    private static final long BUYER_A = 12503L;
    private static final long BUYER_B = 12504L;
    private static final long BUYER_C = 12505L;
    private static final long NON_DEMO_BUYER = 12506L;
    private static final long WITHDRAWN_BUYER = 12507L;
    private static final long HOLDER = 12508L;
    private static final long DEMO_SELLER = 12510L;
    private static final long PLAIN_SELLER = 12511L;
    private static final long DP1 = 12520L;
    private static final long DP2 = 12521L;
    private static final long DP3 = 12522L;
    private static final long PLAIN_PRODUCT = 12523L;
    private static final long HOLDER_PRODUCT = 12524L;
    private static final List<Long> CANDIDATE_ITEMS = List.of(12530L, 12531L, 12532L, 12533L, 12534L, 12535L);
    private static final long ITEM_PLAIN_PRODUCT = 12536L;
    private static final long ITEM_DELETED_REVIEW = 12537L;
    private static final long ITEM_DELIVERED = 12538L;
    private static final long ITEM_NON_DEMO_BUYER = 12539L;
    private static final long ITEM_WITHDRAWN = 12540L;
    private static final int EXISTING_PUBLIC = 106;
    private static final int TARGET = 110;
    private static final String URL = "/api/v1/admin/demo-seed/reviews";
    private static final String DEMO_DOMAIN = "@demo.zslab-mall.com";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    /**
     * 품목 단위 롤백 검증용 — 기본은 실제 동작, 롤백 테스트에서만 N번째 시각 보정에서 예외를 심는다(테스트마다 초기화). ReviewService를 감시하면
     * 스파이가 그 서비스의 트랜잭션 안쪽에 들어가 적재기 트랜잭션 없이도 롤백되므로, 리뷰 INSERT가 끝난 뒤 단계에서 실패시킨다.
     */
    @MockitoSpyBean
    private DemoSeedRepository demoSeedRepository;

    private DemoSeedFixture fixture;
    private LocalDateTime confirmedAt;

    @BeforeEach
    void setUp() {
        fixture = new DemoSeedFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller WHERE company_name LIKE '데모 %' AND id NOT BETWEEN ? AND ?",
                Long.class, BAND_FROM, BAND_TO)).as("대역 밖 데모 셀러가 있으면 대상이 섞인다").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review r JOIN `user` u ON u.id = r.buyer_id WHERE u.email LIKE ? "
                + "AND r.buyer_id NOT BETWEEN ? AND ?", Long.class, "%" + DEMO_DOMAIN, BAND_FROM, BAND_TO))
                .as("대역 밖 데모 구매자 리뷰가 있으면 목표 판정이 섞인다").isZero();

        LocalDateTime userCreatedAt = LocalDateTime.now().minusDays(60);
        confirmedAt = LocalDateTime.now().minusDays(20);
        fixture.seedUser(SUPER_ADMIN, "dsr-super@example.com", "SUPER_ADMIN", userCreatedAt, false);
        fixture.seedUser(OPERATOR, "dsr-operator@example.com", "ADMIN_OPERATOR", userCreatedAt, false);
        fixture.seedUser(BUYER_A, "dsr-a" + DEMO_DOMAIN, "BUYER", userCreatedAt, false);
        fixture.seedUser(BUYER_B, "dsr-b" + DEMO_DOMAIN, "BUYER", userCreatedAt, false);
        fixture.seedUser(BUYER_C, "dsr-c" + DEMO_DOMAIN, "BUYER", userCreatedAt, false);
        fixture.seedUser(NON_DEMO_BUYER, "dsr-plain@example.com", "BUYER", userCreatedAt, false);
        fixture.seedUser(WITHDRAWN_BUYER, "dsr-withdrawn" + DEMO_DOMAIN, "BUYER", userCreatedAt, true);
        fixture.seedUser(HOLDER, "dsr-holder" + DEMO_DOMAIN, "BUYER", userCreatedAt, false);
        fixture.seedSeller(DEMO_SELLER, "데모 리뷰샵", "ACTIVE");
        fixture.seedSeller(PLAIN_SELLER, "일반리뷰샵", "ACTIVE");
        LocalDateTime productCreatedAt = LocalDateTime.now().minusDays(90);
        for (long product : List.of(DP1, DP2, DP3, HOLDER_PRODUCT)) {
            fixture.seedProduct(product, DEMO_SELLER, "SALE", productCreatedAt);
        }
        fixture.seedProduct(PLAIN_PRODUCT, PLAIN_SELLER, "SALE", productCreatedAt);

        // 후보 6: A(DP1·DP2) · B(DP1·DP3) · C(DP2·DP3)
        long[][] candidates = {{12530L, BUYER_A, DP1}, {12531L, BUYER_A, DP2}, {12532L, BUYER_B, DP1}, {12533L, BUYER_B, DP3},
                {12534L, BUYER_C, DP2}, {12535L, BUYER_C, DP3}};
        for (long[] candidate : candidates) {
            fixture.seedOrderItem(candidate[0], candidate[1], candidate[2], DEMO_SELLER, "CONFIRMED", confirmedAt);
        }
        // 제외 5: 비데모 셀러 상품 · 리뷰 이력(삭제) · 미확정 · 비데모 구매자 · 탈퇴 구매자
        fixture.seedOrderItem(ITEM_PLAIN_PRODUCT, BUYER_A, PLAIN_PRODUCT, PLAIN_SELLER, "CONFIRMED", confirmedAt);
        fixture.seedOrderItem(ITEM_DELETED_REVIEW, BUYER_A, DP1, DEMO_SELLER, "CONFIRMED", confirmedAt);
        fixture.insertReview(12707L, ITEM_DELETED_REVIEW, DP1, BUYER_A, "삭제된 이전 리뷰", "VISIBLE", true);
        fixture.seedOrderItem(ITEM_DELIVERED, BUYER_B, DP2, DEMO_SELLER, "DELIVERED", null);
        fixture.seedOrderItem(ITEM_NON_DEMO_BUYER, NON_DEMO_BUYER, DP1, DEMO_SELLER, "CONFIRMED", confirmedAt);
        fixture.seedOrderItem(ITEM_WITHDRAWN, WITHDRAWN_BUYER, DP1, DEMO_SELLER, "CONFIRMED", confirmedAt);

        // 기존 공개 106건(+숨김 1건은 목표에 안 센다) → 부족분 4
        for (int index = 0; index < EXISTING_PUBLIC; index++) {
            fixture.insertReview(12600L + index, 12600L + index, HOLDER_PRODUCT, HOLDER, "기존 리뷰 " + index, "VISIBLE", false);
        }
        fixture.insertReview(12706L, 12706L, HOLDER_PRODUCT, HOLDER, "숨긴 기존 리뷰", "HIDDEN", false);
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
        assertThat(newReviewCount()).isZero();
        assertThat(auditCount()).isZero();

        mockMvc.perform(request(true).with(authHeaders.admin(SUPER_ADMIN))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("dryRun(기본값): 현재 106 · 목표 110 · 후보 6 · 계획 4 · 쓰기·감사 0")
    void dryRun_writesNothing() throws Exception {
        mockMvc.perform(post(URL).with(authHeaders.admin(SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.publicCountBefore").value(EXISTING_PUBLIC))
                .andExpect(jsonPath("$.target").value(TARGET))
                .andExpect(jsonPath("$.candidateCount").value(CANDIDATE_ITEMS.size()))
                .andExpect(jsonPath("$.plannedCount").value(TARGET - EXISTING_PUBLIC))
                .andExpect(jsonPath("$.createdCount").value(0));
        assertThat(newReviewCount()).isZero();
        assertThat(auditCount()).isZero();
    }

    @Test
    @DisplayName("실행: 공개 110 도달 · 작성자 = 품목 구매자 · 후보 품목만(제외 5 무변경) · 구매자 3명 분산 · 기존 키워드만 · 감사 1건")
    void execute_reachesTarget() throws Exception {
        long keywordCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM review_keyword", Long.class);
        mockMvc.perform(request(false).with(authHeaders.admin(SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(TARGET - EXISTING_PUBLIC))
                .andExpect(jsonPath("$.failedItems.length()").value(0));

        assertThat(demoPublicCount()).isEqualTo(TARGET);
        List<Map<String, Object>> created = newReviews();
        assertThat(created).hasSize(TARGET - EXISTING_PUBLIC);
        for (Map<String, Object> review : created) {
            assertThat(CANDIDATE_ITEMS).contains(((Number) review.get("order_item_id")).longValue());
            assertThat(review.get("buyer_id")).as("작성자 = 품목 구매자").isEqualTo(review.get("order_buyer_id"));
            assertThat(review.get("status")).isEqualTo("VISIBLE");
        }
        assertThat(created.stream().map(review -> ((Number) review.get("buyer_id")).longValue()).distinct().count()).isEqualTo(3);
        for (long excluded : List.of(ITEM_PLAIN_PRODUCT, ITEM_DELIVERED, ITEM_NON_DEMO_BUYER, ITEM_WITHDRAWN)) {
            assertThat(reviewCountForItem(excluded)).as("제외 품목 %d", excluded).isZero();
        }
        assertThat(reviewCountForItem(ITEM_DELETED_REVIEW)).as("리뷰 이력 품목은 삭제분 1건 그대로").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_keyword", Long.class)).isEqualTo(keywordCountBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_keyword_selection s JOIN review_keyword k ON k.id = s.keyword_id "
                + "JOIN review r ON r.id = s.review_id WHERE r.product_id BETWEEN ? AND ? AND k.top_category_id IS NOT NULL", Long.class,
                BAND_FROM, BAND_TO)).as("카테고리 없는 상품은 기본 세트만").isZero();
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("재호출: 계획·생성 0 · 실행마다 감사 1건")
    void recall_addsNothing() throws Exception {
        mockMvc.perform(request(false).with(authHeaders.admin(SUPER_ADMIN))).andExpect(status().isOk());
        mockMvc.perform(request(false).with(authHeaders.admin(SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicCountBefore").value(TARGET))
                .andExpect(jsonPath("$.plannedCount").value(0))
                .andExpect(jsonPath("$.createdCount").value(0));
        assertThat(demoPublicCount()).isEqualTo(TARGET);
        assertThat(auditCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("시각 불변식: 작성 시각 ≥ 구매확정 · 확정 + 5일 11시간 이내 · 현재 이전 · updated_at = created_at")
    void timestamps_areShiftedAfterConfirm() throws Exception {
        mockMvc.perform(request(false).with(authHeaders.admin(SUPER_ADMIN))).andExpect(status().isOk());
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> created = newReviews();
        assertThat(created).isNotEmpty();
        for (Map<String, Object> review : created) {
            LocalDateTime written = time(review.get("created_at"));
            LocalDateTime confirmed = time(review.get("confirmed_at"));
            assertThat(written).isAfterOrEqualTo(confirmed).isBefore(confirmed.plusDays(5).plusHours(11)).isBefore(now);
            assertThat(time(review.get("updated_at"))).isEqualTo(written);
        }
    }

    @Test
    @DisplayName("품목 단위 롤백: 두 번째 리뷰 INSERT 뒤 시각 보정 단계 예외 → 그 리뷰 행은 롤백 · 나머지 3건 적재 · 실패 품목 표시")
    void midWriteFailure_rollsBackOnlyThatItem() throws Exception {
        AtomicInteger shiftCalls = new AtomicInteger();
        AtomicLong failedReviewId = new AtomicLong();
        // 저장소는 인터페이스 프록시라 callRealMethod가 안 된다 — 주입하지 않는 호출은 스파이의 기본 응답(원래 빈 위임)으로 넘긴다
        Answer<?> delegate = Mockito.mockingDetails(demoSeedRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            if (shiftCalls.incrementAndGet() == 2) {
                failedReviewId.set(invocation.getArgument(0));   // 이 리뷰 행은 ReviewService가 이미 INSERT·flush했다
                throw new IllegalStateException("테스트 주입 실패");
            }
            return delegate.answer(invocation);
        }).when(demoSeedRepository).shiftReviewTime(any(), any());

        String body = mockMvc.perform(request(false).with(authHeaders.admin(SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 롤백 단언을 응답 단언보다 먼저 둔다 — 적재기 트랜잭션이 없으면 create가 따로 커밋돼 이 행이 남아 여기서 깨진다
        assertThat(failedReviewId.get()).as("예외 주입 시점에 리뷰 행이 이미 만들어졌어야 롤백을 검증한다").isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review WHERE id = ?", Long.class, failedReviewId.get()))
                .as("실패 품목의 리뷰는 롤백").isZero();
        assertThat(newReviews()).hasSize(TARGET - EXISTING_PUBLIC - 1);
        assertThat(JsonPath.<Integer>read(body, "$.createdCount")).isEqualTo(TARGET - EXISTING_PUBLIC - 1);
        List<String> failedItems = JsonPath.read(body, "$.failedItems[*].orderItemPublicId");
        assertThat(failedItems).hasSize(1);
        long failedItemId = Long.parseLong(failedItems.get(0).substring("oit_DSI".length(), "oit_DSI".length() + 5));
        assertThat(reviewCountForItem(failedItemId)).isZero();
        assertThat(JsonPath.<List<String>>read(body, "$.failedItems[*].reason")).containsExactly("IllegalStateException: 테스트 주입 실패");
    }

    private MockHttpServletRequestBuilder request(boolean dryRun) {
        return post(URL).param("dryRun", String.valueOf(dryRun));
    }

    /** 이번 호출로 생긴 리뷰(픽스처 public_id 접두사 제외) + 품목 구매자·구매확정 시각. */
    private List<Map<String, Object>> newReviews() {
        return jdbc.queryForList("SELECT r.order_item_id, r.buyer_id, r.status, r.created_at, r.updated_at, o.buyer_id AS order_buyer_id, "
                + "oi.confirmed_at FROM review r JOIN order_item oi ON oi.id = r.order_item_id JOIN `order` o ON o.id = oi.order_id "
                + "WHERE r.product_id BETWEEN ? AND ? AND r.public_id NOT LIKE 'rvw_DSR%'", BAND_FROM, BAND_TO);
    }

    private long newReviewCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM review WHERE product_id BETWEEN ? AND ? AND public_id NOT LIKE 'rvw_DSR%'",
                Long.class, BAND_FROM, BAND_TO);
    }

    private long reviewCountForItem(long orderItemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM review WHERE order_item_id = ?", Long.class, orderItemId);
    }

    private long demoPublicCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM review r JOIN `user` u ON u.id = r.buyer_id WHERE u.email LIKE ? "
                + "AND u.withdrawn_at IS NULL AND r.status = 'VISIBLE' AND r.deleted_at IS NULL", Long.class, "%" + DEMO_DOMAIN);
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'DEMO_SEED' AND actor_user_id = ?", Long.class,
                SUPER_ADMIN);
    }

    private static LocalDateTime time(Object value) {
        return value == null ? null : ((Timestamp) value).toLocalDateTime();
    }
}
