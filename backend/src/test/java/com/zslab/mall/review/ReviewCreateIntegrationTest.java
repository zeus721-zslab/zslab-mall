package com.zslab.mall.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthCookies;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.common.security.TokenProvider;
import com.zslab.mall.review.exception.ReviewAlreadyExistsException;
import com.zslab.mall.review.service.ReviewService;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 리뷰 작성 통합 테스트(Track 106-1 STEP 1·실 MariaDB·실 커밋). 자격(본인·구매확정·품목당 1개)·키워드 소속·option_label 스냅샷·동시 작성을
 * HTTP와 서비스 경합으로 검증한다.
 */
@AutoConfigureMockMvc
class ReviewCreateIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10600L;
    private static final long BAND_TO = 10619L;
    private static final long BUYER = 10601L;
    private static final long OTHER_BUYER = 10602L;
    private static final long SELLER = 10603L;
    private static final long PRODUCT = 10604L;
    private static final long VARIANT = 10605L;
    private static final long CATEGORY = 10606L;
    private static final long OTHER_CATEGORY = 10607L;
    private static final long ORDER = 10608L;
    private static final long ITEM = 10609L;
    private static final long UNCONFIRMED_ORDER = 10610L;
    private static final long UNCONFIRMED_ITEM = 10611L;
    private static final long OWN_CATEGORY_KEYWORD = 10612L;
    private static final long OTHER_CATEGORY_KEYWORD = 10613L;
    private static final String OPTION_LABEL = "색상: 블랙 / 사이즈: M";
    private static final long RACE_TIMEOUT_SECONDS = 20L;
    private static final int CONTENT_COLUMN_LENGTH = 1000;
    private static final String CREATE_URL = "/api/v1/reviews";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private TokenProvider tokenProvider;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private ReviewFixture fixture;
    private String itemPid;
    private String unconfirmedItemPid;

    @BeforeEach
    void setUp() {
        fixture = new ReviewFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(BUYER);
        fixture.seedUser(OTHER_BUYER);
        fixture.seedCatalog(SELLER, PRODUCT, VARIANT, CATEGORY);
        itemPid = fixture.seedItem(ORDER, ITEM, BUYER, PRODUCT, VARIANT, SELLER, "CONFIRMED", OPTION_LABEL);
        unconfirmedItemPid = fixture.seedItem(UNCONFIRMED_ORDER, UNCONFIRMED_ITEM, BUYER, PRODUCT, VARIANT, SELLER, "DELIVERED", null);
        fixture.insertCategoryKeyword(OWN_CATEGORY_KEYWORD, "T106_OWN_CATEGORY", CATEGORY);
        fixture.insertCategoryKeyword(OTHER_CATEGORY_KEYWORD, "T106_OTHER_CATEGORY", OTHER_CATEGORY);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("C1 인증 없음 → 401 UNAUTHENTICATED·리뷰 행 없음")
    void create_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post(CREATE_URL).contentType(MediaType.APPLICATION_JSON).content(body(itemPid, List.of())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(reviewCount()).isZero();
    }

    @Test
    @DisplayName("C2 구매자 쿠키만 있고 CSRF 토큰 없음 → 403·리뷰 행 없음")
    void create_withoutCsrf_returns403() throws Exception {
        Cookie buyerCookie = new Cookie(AuthCookies.BUYER_COOKIE, tokenProvider.issue(BUYER, ActorRole.BUYER));

        mockMvc.perform(post(CREATE_URL).cookie(buyerCookie).contentType(MediaType.APPLICATION_JSON).content(body(itemPid, List.of())))
                .andExpect(status().isForbidden());

        assertThat(reviewCount()).isZero();
    }

    @Test
    @DisplayName("C3 성공 → 201·VISIBLE·option_label 스냅샷 복사·기본 세트와 자기 카테고리 키워드 저장")
    void create_confirmedOwnItem_returns201() throws Exception {
        mockMvc.perform(post(CREATE_URL).with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(itemPid, List.of("DELIVERY_FAST", "T106_OWN_CATEGORY"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewId").value(org.hamcrest.Matchers.startsWith("rvw_")));

        assertThat(jdbc.queryForMap("SELECT rating, content, option_label, status, buyer_id, product_id, helpful_count "
                + "FROM review WHERE order_item_id = ?", ITEM))
                .containsEntry("rating", 5)
                .containsEntry("content", "좋아요")
                .containsEntry("option_label", OPTION_LABEL)
                .containsEntry("status", "VISIBLE")
                .containsEntry("buyer_id", BUYER)
                .containsEntry("product_id", PRODUCT)
                .containsEntry("helpful_count", 0);
        // product_id 불변식: 리뷰 상품 = 품목 상품(요청값이 아니라 order_item에서 채운다).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review r JOIN order_item oi ON oi.id = r.order_item_id "
                + "WHERE r.order_item_id = ? AND r.product_id = oi.product_id", Integer.class, ITEM)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT k.code FROM review_keyword_selection s JOIN review_keyword k ON k.id = s.keyword_id "
                + "JOIN review r ON r.id = s.review_id WHERE r.order_item_id = ? ORDER BY k.code", String.class, ITEM))
                .containsExactly("DELIVERY_FAST", "T106_OWN_CATEGORY");
    }

    @Test
    @DisplayName("C4 타인 품목 → 404 ORDER_NOT_FOUND(존재 은닉)·리뷰 행 없음")
    void create_otherBuyersItem_returns404() throws Exception {
        mockMvc.perform(post(CREATE_URL).with(authHeaders.buyer(OTHER_BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(itemPid, List.of())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        assertThat(reviewCount()).isZero();
    }

    @Test
    @DisplayName("C5 구매확정 전(DELIVERED) 품목 → 422 REVIEW_NOT_ELIGIBLE·리뷰 행 없음")
    void create_unconfirmedItem_returns422() throws Exception {
        mockMvc.perform(post(CREATE_URL).with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(unconfirmedItemPid, List.of())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_ELIGIBLE"));

        assertThat(reviewCount()).isZero();
    }

    @Test
    @DisplayName("C6 같은 품목 두 번째 작성 → 409 REVIEW_ALREADY_EXISTS·리뷰 1개 유지")
    void create_secondReviewForSameItem_returns409() throws Exception {
        mockMvc.perform(post(CREATE_URL).with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(itemPid, List.of())))
                .andExpect(status().isCreated());

        mockMvc.perform(post(CREATE_URL).with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(itemPid, List.of())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_EXISTS"));

        assertThat(reviewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("C7 다른 카테고리 전용 키워드 → 400 MALFORMED_REQUEST·리뷰 행 없음")
    void create_otherCategoryKeyword_returns400() throws Exception {
        mockMvc.perform(post(CREATE_URL).with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(itemPid, List.of("DELIVERY_FAST", "T106_OTHER_CATEGORY"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertThat(reviewCount()).isZero();
    }

    @Test
    @DisplayName("C8 별점 범위 밖(6) → 400 VALIDATION_FAILED")
    void create_ratingOutOfRange_returns400() throws Exception {
        String outOfRange = """
                {"orderItemId":"%s","rating":6,"content":"좋아요"}""".formatted(itemPid);

        mockMvc.perform(post(CREATE_URL).with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON).content(outOfRange))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(reviewCount()).isZero();
    }

    @Test
    @DisplayName("C9 같은 품목 동시 작성 → 정확히 1건 성공·나머지 REVIEW_ALREADY_EXISTS(uk_review_order_item)")
    void create_concurrentForSameItem_onlyOneSucceeds() throws Exception {
        Callable<String> write = () -> {
            try {
                reviewService.create(BUYER, itemPid, 4, List.of(), "동시 작성", List.of());
                return "OK";
            } catch (ReviewAlreadyExistsException exception) {
                return exception.getClass().getSimpleName();
            }
        };

        String[] outcomes = race(write, write);

        assertThat(outcomes).containsExactlyInAnyOrder("OK", "ReviewAlreadyExistsException");
        assertThat(reviewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("C10 uk_review_order_item 외 무결성 위반(본문 1,001자·DTO 우회 서비스 호출) → 409로 바꾸지 않고 원래 예외 전파·리뷰 행 없음")
    void create_otherIntegrityViolation_propagatesOriginal() {
        String tooLong = "가".repeat(CONTENT_COLUMN_LENGTH + 1);

        assertThatThrownBy(() -> reviewService.create(BUYER, itemPid, 4, List.of(), tooLong, List.of()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(ReviewAlreadyExistsException.class);
        assertThat(reviewCount()).isZero();
    }

    // ---------- helpers ----------

    private static String body(String orderItemPid, List<String> keywordCodes) {
        String codes = keywordCodes.stream().map(code -> "\"" + code + "\"").reduce((left, right) -> left + "," + right).orElse("");
        return """
                {"orderItemId":"%s","rating":5,"keywordCodes":[%s],"content":"좋아요"}""".formatted(orderItemPid, codes);
    }

    private int reviewCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM review WHERE product_id = ?", Integer.class, PRODUCT);
    }

    /** 두 작업을 래치로 동시에 풀어 결과 문자열 2개를 돌려준다(ClaimLockRaceIntegrationTest 패턴). */
    private String[] race(Callable<String> first, Callable<String> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> firstResult = pool.submit(gate(first, ready, start));
            Future<String> secondResult = pool.submit(gate(second, ready, start));
            awaitQuietly(ready);
            start.countDown();
            return new String[] {
                    firstResult.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    secondResult.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            };
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<String> gate(Callable<String> work, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            awaitQuietly(start);
            return work.call();
        };
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("경합 래치 대기 중 인터럽트", exception);
        }
    }
}
