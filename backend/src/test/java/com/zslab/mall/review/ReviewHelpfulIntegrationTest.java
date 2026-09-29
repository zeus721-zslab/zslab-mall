package com.zslab.mall.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.review.service.ReviewHelpfulService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 리뷰 도움됐어요 통합 테스트(Track 106-1 STEP 5·실 MariaDB·실 커밋). 로그인 구매자·본인 제외·공개 리뷰만·토글 멱등과, 동시 요청에서
 * helpful_count가 review_helpful 행 수와 일치하는지(교착 없이)를 검증한다.
 */
@AutoConfigureMockMvc
class ReviewHelpfulIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10680L;
    private static final long BAND_TO = 10699L;
    private static final long AUTHOR = 10681L;
    private static final long VOTER = 10682L;
    private static final long SELLER = 10683L;
    private static final long PRODUCT = 10684L;
    private static final long VARIANT = 10685L;
    private static final long CATEGORY = 10686L;
    private static final long REVIEW = 10687L;
    private static final long HIDDEN_REVIEW = 10688L;
    private static final long RACE_REVIEW = 10689L;
    private static final long FIRST_RACE_VOTER = 10690L;
    private static final int RACE_VOTERS = 5;
    private static final long RACE_TIMEOUT_SECONDS = 30L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private ReviewHelpfulService reviewHelpfulService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private ReviewFixture fixture;
    private String reviewPid;
    private String hiddenPid;
    private String racePid;

    @BeforeEach
    void setUp() {
        fixture = new ReviewFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(AUTHOR);
        fixture.seedUser(VOTER);
        fixture.seedCatalog(SELLER, PRODUCT, VARIANT, CATEGORY);
        LocalDateTime now = LocalDateTime.now();
        reviewPid = fixture.insertReview(REVIEW, REVIEW, PRODUCT, AUTHOR, 5, "VISIBLE", null, 0, now);
        hiddenPid = fixture.insertReview(HIDDEN_REVIEW, HIDDEN_REVIEW, PRODUCT, AUTHOR, 5, "HIDDEN", null, 0, now);
        racePid = fixture.insertReview(RACE_REVIEW, RACE_REVIEW, PRODUCT, AUTHOR, 5, "VISIBLE", null, 0, now);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("H1 비로그인 → 추가·취소 401 · 행·수 불변")
    void helpful_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post(helpfulUrl(reviewPid))).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(helpfulUrl(reviewPid))).andExpect(status().isUnauthorized());

        assertThat(helpfulCount(REVIEW)).isZero();
        assertThat(helpfulRows(REVIEW)).isZero();
    }

    @Test
    @DisplayName("H2 본인 리뷰 → 422 REVIEW_NOT_ELIGIBLE / 숨김·없는 리뷰 → 404 REVIEW_NOT_FOUND")
    void helpful_ownOrNonPublicReview_rejected() throws Exception {
        mockMvc.perform(post(helpfulUrl(reviewPid)).with(authHeaders.buyer(AUTHOR)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_ELIGIBLE"));
        mockMvc.perform(post(helpfulUrl(hiddenPid)).with(authHeaders.buyer(VOTER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
        mockMvc.perform(post(helpfulUrl(ReviewFixture.pid("rvw_", "RVNOREVIEW"))).with(authHeaders.buyer(VOTER)))
                .andExpect(status().isNotFound());

        assertThat(helpfulCount(REVIEW)).isZero();
        assertThat(helpfulCount(HIDDEN_REVIEW)).isZero();
    }

    @Test
    @DisplayName("H3 토글·멱등: 추가 1 → 재추가 1 유지 → 취소 0 → 재취소 0 유지(수 = 행 수)")
    void helpful_toggleIsIdempotent() throws Exception {
        mockMvc.perform(post(helpfulUrl(reviewPid)).with(authHeaders.buyer(VOTER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helped").value(true))
                .andExpect(jsonPath("$.helpfulCount").value(1));
        mockMvc.perform(post(helpfulUrl(reviewPid)).with(authHeaders.buyer(VOTER)))
                .andExpect(jsonPath("$.helped").value(true))
                .andExpect(jsonPath("$.helpfulCount").value(1));
        assertThat(helpfulRows(REVIEW)).isEqualTo(1);

        mockMvc.perform(delete(helpfulUrl(reviewPid)).with(authHeaders.buyer(VOTER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helped").value(false))
                .andExpect(jsonPath("$.helpfulCount").value(0));
        mockMvc.perform(delete(helpfulUrl(reviewPid)).with(authHeaders.buyer(VOTER)))
                .andExpect(jsonPath("$.helpfulCount").value(0));
        assertThat(helpfulRows(REVIEW)).isZero();
    }

    @Test
    @DisplayName("H4 동시: 서로 다른 5명 동시 추가 → 수 5·행 5(교착 없음) / 같은 사람 5회 동시 추가 → 수 1·행 1")
    void helpful_concurrentRequests_countMatchesRows() throws Exception {
        List<Callable<String>> distinctVoters = new ArrayList<>();
        for (int index = 0; index < RACE_VOTERS; index++) {
            long voter = FIRST_RACE_VOTER + index;
            distinctVoters.add(() -> {
                reviewHelpfulService.add(voter, racePid);
                return "OK";
            });
        }
        assertThat(race(distinctVoters)).containsOnly("OK");
        assertThat(helpfulCount(RACE_REVIEW)).isEqualTo(RACE_VOTERS);
        assertThat(helpfulRows(RACE_REVIEW)).isEqualTo(RACE_VOTERS);

        List<Callable<String>> sameVoter = new ArrayList<>();
        for (int index = 0; index < RACE_VOTERS; index++) {
            sameVoter.add(() -> {
                reviewHelpfulService.add(VOTER, reviewPid);
                return "OK";
            });
        }
        assertThat(race(sameVoter)).containsOnly("OK");
        assertThat(helpfulCount(REVIEW)).isEqualTo(1);
        assertThat(helpfulRows(REVIEW)).isEqualTo(1);
    }

    // ---------- helpers ----------

    private static String helpfulUrl(String reviewPublicId) {
        return "/api/v1/reviews/" + reviewPublicId + "/helpful";
    }

    private int helpfulCount(long reviewId) {
        return jdbc.queryForObject("SELECT helpful_count FROM review WHERE id = ?", Integer.class, reviewId);
    }

    private int helpfulRows(long reviewId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM review_helpful WHERE review_id = ?", Integer.class, reviewId);
    }

    /** 작업들을 래치로 동시에 풀어 결과를 돌려준다(ClaimLockRaceIntegrationTest 패턴의 N개 확장). 예외는 클래스 이름으로 돌려준다. */
    private List<String> race(List<Callable<String>> works) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(works.size());
        CountDownLatch ready = new CountDownLatch(works.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> work : works) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    awaitQuietly(start);
                    try {
                        return work.call();
                    } catch (RuntimeException exception) {
                        return exception.getClass().getSimpleName();
                    }
                }));
            }
            awaitQuietly(ready);
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
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
