package com.zslab.mall.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.review.service.ReviewService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 리뷰 한 줄 요약 비동기 재계산 통합 테스트(Track 106-1 STEP 7·실 MariaDB·실 커밋). 작성·숨김·삭제 커밋 후 전용 실행기가 요약을 다시 저장하는지,
 * 롤백된 쓰기는 요약을 건드리지 않는지, 공개 리뷰가 0건이면 요약이 없어지는지를 기다려(Awaitility) 확인한다.
 */
@AutoConfigureMockMvc
class ReviewSummaryIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10720L;
    private static final long BAND_TO = 10739L;
    private static final long ADMIN = 10721L;
    private static final long BUYER = 10722L;
    private static final long SELLER = 10723L;
    private static final long PRODUCT = 10724L;
    private static final long VARIANT = 10725L;
    private static final long CATEGORY = 10726L;
    private static final long FIRST_ORDER = 10727L;
    private static final long FIRST_ITEM = 10728L;
    private static final long SECOND_ORDER = 10729L;
    private static final long SECOND_ITEM = 10730L;
    private static final Duration SUMMARY_WAIT = Duration.ofSeconds(10);
    private static final Duration NO_CHANGE_WINDOW = Duration.ofSeconds(1);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private ReviewService reviewService;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private ReviewFixture fixture;
    private String firstItemPid;
    private String secondItemPid;

    @BeforeEach
    void setUp() {
        fixture = new ReviewFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(ADMIN);
        fixture.seedUser(BUYER);
        fixture.seedCatalog(SELLER, PRODUCT, VARIANT, CATEGORY);
        firstItemPid = fixture.seedItem(FIRST_ORDER, FIRST_ITEM, BUYER, PRODUCT, VARIANT, SELLER, "CONFIRMED", null);
        secondItemPid = fixture.seedItem(SECOND_ORDER, SECOND_ITEM, BUYER, PRODUCT, VARIANT, SELLER, "CONFIRMED", null);
    }

    @AfterEach
    void tearDown() {
        // 테스트가 끝난 뒤 늦게 도착한 재계산이 다음 테스트에 새지 않게 요약 행이 기대 상태에 이른 뒤 정리한다(각 테스트가 마지막에 기다린다).
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("S1 작성 커밋 후 → 요약 생성(평균·리뷰 수·많이 고른 키워드 템플릿) · 공개 요약 API에 노출")
    void create_thenSummaryGenerated() throws Exception {
        createReview(firstItemPid, 4, "\"DELIVERY_FAST\"");

        await().atMost(SUMMARY_WAIT).until(() -> summaryCount() == 1);
        assertThat(summaryText()).isEqualTo("평균 별점 4.0점(리뷰 1개) · '배송이 빨라요' 평가가 많아요.");
        mockMvc.perform(get("/api/v1/products/" + ReviewFixture.pid("prd_", "RVP" + PRODUCT) + "/reviews/summary"))
                .andExpect(jsonPath("$.summaryText").value("평균 별점 4.0점(리뷰 1개) · '배송이 빨라요' 평가가 많아요."));
    }

    @Test
    @DisplayName("S2 숨김 커밋 후 → 재계산 반영(공개 리뷰만) / 해제 후 → 복귀")
    void hide_thenSummaryRecalculated() throws Exception {
        createReview(firstItemPid, 5, "");
        String lowReview = createReview(secondItemPid, 1, "");
        await().atMost(SUMMARY_WAIT).until(() -> reviewCountInSummary() == 2);
        assertThat(summaryText()).startsWith("평균 별점 3.0점(리뷰 2개)");

        changeStatus(lowReview, "HIDDEN");

        await().atMost(SUMMARY_WAIT).until(() -> reviewCountInSummary() == 1);
        assertThat(summaryText()).startsWith("평균 별점 5.0점(리뷰 1개)");

        changeStatus(lowReview, "VISIBLE");
        await().atMost(SUMMARY_WAIT).until(() -> reviewCountInSummary() == 2);
    }

    @Test
    @DisplayName("S3 유일한 리뷰 삭제 → 요약 행 삭제(요약 없음) · 공개 요약 API summaryText 키 없음")
    void deleteLastReview_thenSummaryRemoved() throws Exception {
        String reviewId = createReview(firstItemPid, 3, "");
        await().atMost(SUMMARY_WAIT).until(() -> summaryCount() == 1);

        mockMvc.perform(delete("/api/v1/reviews/" + reviewId).with(authHeaders.buyer(BUYER))).andExpect(status().isNoContent());

        await().atMost(SUMMARY_WAIT).until(() -> summaryCount() == 0);
        mockMvc.perform(get("/api/v1/products/" + ReviewFixture.pid("prd_", "RVP" + PRODUCT) + "/reviews/summary"))
                .andExpect(jsonPath("$.summaryText").doesNotExist());
    }

    @Test
    @DisplayName("S4 이벤트 발행 뒤 롤백 → 커밋 후 리스너 미전달 · 재계산 없음(요약 updated_at·리뷰 수 불변) · 롤백된 리뷰 행 없음")
    void rolledBackAfterPublish_doesNotRecalculate() throws Exception {
        createReview(firstItemPid, 4, "");
        await().atMost(SUMMARY_WAIT).until(() -> summaryCount() == 1);
        LocalDateTime committedUpdatedAt = summaryUpdatedAt();

        // 서비스가 이벤트를 발행한 뒤 바깥 트랜잭션을 롤백한다(발행 이전에 실패하는 422 경로와 달리 리스너 단계가 실제로 갈린다).
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            reviewService.create(BUYER, secondItemPid, 1, List.of(), "롤백될 리뷰", List.of());
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review WHERE order_item_id = ?", Integer.class, SECOND_ITEM)).isZero();
        await().during(NO_CHANGE_WINDOW).atMost(SUMMARY_WAIT)
                .until(() -> committedUpdatedAt.equals(summaryUpdatedAt()) && reviewCountInSummary() == 1);
    }

    // ---------- helpers ----------

    private String createReview(String itemPid, int rating, String keywordCodes) throws Exception {
        String body = mockMvc.perform(post("/api/v1/reviews").with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(itemPid, rating, keywordCodes)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return jsonMapper.readTree(body).get("reviewId").asText();
    }

    private static String createBody(String itemPid, int rating, String keywordCodes) {
        return "{\"orderItemId\":\"" + itemPid + "\",\"rating\":" + rating + ",\"content\":\"요약 테스트\",\"keywordCodes\":["
                + keywordCodes + "]}";
    }

    private void changeStatus(String reviewId, String target) throws Exception {
        mockMvc.perform(patch("/api/v1/admin/reviews/" + reviewId + "/status").with(authHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + target + "\",\"reason\":\"테스트\"}"))
                .andExpect(status().isNoContent());
    }

    private int summaryCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_review_summary WHERE product_id = ?", Integer.class, PRODUCT);
    }

    private int reviewCountInSummary() {
        return jdbc.queryForList("SELECT review_count FROM product_review_summary WHERE product_id = ?", Integer.class, PRODUCT)
                .stream().findFirst().orElse(0);
    }

    private LocalDateTime summaryUpdatedAt() {
        return jdbc.queryForObject("SELECT updated_at FROM product_review_summary WHERE product_id = ?", LocalDateTime.class, PRODUCT);
    }

    private String summaryText() {
        return jdbc.queryForObject("SELECT summary_text FROM product_review_summary WHERE product_id = ?", String.class, PRODUCT);
    }
}
