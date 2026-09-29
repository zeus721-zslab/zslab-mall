package com.zslab.mall.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * 주문 응답 품목 리뷰 상태·상품 목록 별점 통합 테스트(Track 106-1 STEP 8·실 MariaDB). 리뷰 상태 3종(+ 삭제 리뷰 = WRITTEN·reviewId 없음)과
 * 공개 리뷰 기준 목록 평균·개수, 그리고 행 수가 늘어도 쿼리 수가 같음(배치 조회·N+1 없음)을 Hibernate Statistics로 확인한다
 * (BuyerOrderClaimTabIntegrationTest 선례).
 */
@AutoConfigureMockMvc
class ReviewOrderLinkIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10740L;
    private static final long BAND_TO = 10779L;
    private static final long BUYER = 10741L;
    private static final long SELLER = 10742L;
    private static final long PRODUCT = 10743L;
    private static final long VARIANT = 10744L;
    private static final long CATEGORY = 10745L;
    private static final long ORDER = 10746L;
    private static final long ITEM_WRITABLE = 10747L;
    private static final long ITEM_WRITTEN = 10748L;
    private static final long ITEM_DELETED_REVIEW = 10749L;
    private static final long ITEM_NOT_ELIGIBLE = 10750L;
    private static final long REVIEW_VISIBLE = 10751L;
    private static final long REVIEW_DELETED = 10752L;
    private static final long REVIEW_HIDDEN = 10753L;
    private static final long REVIEW_SECOND_VISIBLE = 10754L;
    private static final long OTHER_PRODUCT = 10755L;
    private static final long OTHER_VARIANT = 10756L;
    /** 숨김 처리된 리뷰가 있는 품목·그 리뷰(PR2 review.hidden). */
    private static final long ITEM_HIDDEN_REVIEW = 10757L;
    private static final long REVIEW_HIDDEN_WRITTEN = 10758L;
    /** N+1 확인용으로 뒤에 더하는 상품 대역 시작(상품·옵션 id를 2개씩 쓴다·10760~10765). */
    private static final long GROWTH_BASE = 10760L;
    private static final int GROWTH_ROWS = 3;
    private static final long SECOND_BUYER = 10770L;
    private static final long SECOND_ORDER = 10771L;
    /** 둘째 주문의 품목·리뷰 id 시작(10772~10775). */
    private static final long SECOND_ORDER_FIRST_ITEM = 10772L;
    /** 공개 리뷰 목록에 더하는 리뷰·사진 id(10778~10779). */
    private static final long EXTRA_PUBLIC_REVIEW = 10778L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private ReviewFixture fixture;
    private String orderPid;
    private String writtenReviewPid;
    private String sellerPid;

    @BeforeEach
    void setUp() {
        fixture = new ReviewFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(BUYER);
        fixture.seedCatalog(SELLER, PRODUCT, VARIANT, CATEGORY);
        sellerPid = ReviewFixture.pid("slr_", "RVS" + SELLER);
        fixture.seedItem(ORDER, ITEM_WRITABLE, BUYER, PRODUCT, VARIANT, SELLER, "CONFIRMED", null);
        orderPid = ReviewFixture.pid("ord_", "RVO" + ORDER);
        fixture.seedItemInOrder(ORDER, ITEM_WRITTEN, PRODUCT, VARIANT, SELLER, "CONFIRMED");
        fixture.seedItemInOrder(ORDER, ITEM_DELETED_REVIEW, PRODUCT, VARIANT, SELLER, "CONFIRMED");
        fixture.seedItemInOrder(ORDER, ITEM_NOT_ELIGIBLE, PRODUCT, VARIANT, SELLER, "DELIVERED");
        fixture.seedItemInOrder(ORDER, ITEM_HIDDEN_REVIEW, PRODUCT, VARIANT, SELLER, "CONFIRMED");
        LocalDateTime now = LocalDateTime.now();
        writtenReviewPid = fixture.insertReview(REVIEW_VISIBLE, ITEM_WRITTEN, PRODUCT, BUYER, 5, "VISIBLE", null, 0, now);
        fixture.insertReview(REVIEW_HIDDEN_WRITTEN, ITEM_HIDDEN_REVIEW, PRODUCT, BUYER, 2, "HIDDEN", null, 0, now);
        fixture.insertReview(REVIEW_DELETED, ITEM_DELETED_REVIEW, PRODUCT, BUYER, 1, "VISIBLE", null, 0, now);
        jdbc.update("UPDATE review SET deleted_at = NOW(6) WHERE id = ?", REVIEW_DELETED);
        // 목록 별점용 — 품목 id는 주문과 무관한 대역 값(FK 끔·uk_review_order_item만 만족)
        fixture.insertReview(REVIEW_HIDDEN, REVIEW_HIDDEN, PRODUCT, BUYER, 1, "HIDDEN", null, 0, now);
        fixture.insertReview(REVIEW_SECOND_VISIBLE, REVIEW_SECOND_VISIBLE, PRODUCT, BUYER, 4, "VISIBLE", null, 0, now);
        fixture.seedCatalog(SELLER + BAND_TO, OTHER_PRODUCT, OTHER_VARIANT, CATEGORY);
        jdbc.update("UPDATE product SET seller_id = ? WHERE id = ?", SELLER, OTHER_PRODUCT);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.cleanup(BAND_FROM + BAND_TO, BAND_TO + BAND_TO);
    }

    @Test
    @DisplayName("O1 주문 상세 품목 review: 확정·리뷰 없음 WRITABLE · 리뷰 있음 WRITTEN+reviewId · 삭제 리뷰 WRITTEN(reviewId 없음) · 확정 전 NOT_ELIGIBLE")
    void orderDetail_itemReviewStatus() throws Exception {
        mockMvc.perform(get("/api/v1/orders/" + orderPid).with(authHeaders.buyer(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(itemReview("sellers[0].items", ITEM_WRITABLE) + ".status").value("WRITABLE"))
                .andExpect(jsonPath(itemReview("sellers[0].items", ITEM_WRITABLE) + ".reviewId").isEmpty())
                .andExpect(jsonPath(itemReview("sellers[0].items", ITEM_WRITTEN) + ".status").value("WRITTEN"))
                .andExpect(jsonPath(itemReview("sellers[0].items", ITEM_WRITTEN) + ".reviewId").value(writtenReviewPid))
                .andExpect(jsonPath(itemReview("sellers[0].items", ITEM_DELETED_REVIEW) + ".status").value("WRITTEN"))
                .andExpect(jsonPath(itemReview("sellers[0].items", ITEM_DELETED_REVIEW) + ".reviewId").isEmpty())
                .andExpect(jsonPath(itemReview("sellers[0].items", ITEM_NOT_ELIGIBLE) + ".status").value("NOT_ELIGIBLE"));
    }

    @Test
    @DisplayName("O1-1 review.hidden: 숨김 리뷰 품목 true · 공개 리뷰 품목 false · 미작성·확정 전 품목 키 없음(상세·목록 동일)")
    void orderItemReview_hiddenFlag() throws Exception {
        mockMvc.perform(get("/api/v1/orders/" + orderPid).with(authHeaders.buyer(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(itemReview("sellers[0].items", ITEM_HIDDEN_REVIEW) + ".status").value("WRITTEN"))
                .andExpect(jsonPath(itemReview("sellers[0].items", ITEM_HIDDEN_REVIEW) + ".hidden").value(true))
                .andExpect(jsonPath(itemReview("sellers[0].items", ITEM_WRITTEN) + ".hidden").value(false))
                // 키 없음 단언 전에 품목을 찾았음을 먼저 고정한다(못 찾아도 빈 배열이라 isEmpty가 통과하는 false-green 방지).
                .andExpect(jsonPath(itemReview("sellers[0].items", ITEM_WRITABLE) + ".status").value("WRITABLE"))
                .andExpect(jsonPath(itemReview("sellers[0].items", ITEM_NOT_ELIGIBLE) + ".status").value("NOT_ELIGIBLE"))
                .andExpect(jsonPath(itemReview("sellers[0].items", ITEM_WRITABLE) + ".hidden").isEmpty())
                .andExpect(jsonPath(itemReview("sellers[0].items", ITEM_NOT_ELIGIBLE) + ".hidden").isEmpty());
        mockMvc.perform(get("/api/v1/orders").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(itemReview("items[0].items", ITEM_HIDDEN_REVIEW) + ".hidden").value(true))
                .andExpect(jsonPath(itemReview("items[0].items", ITEM_WRITTEN) + ".hidden").value(false))
                .andExpect(jsonPath(itemReview("items[0].items", ITEM_WRITABLE) + ".status").value("WRITABLE"))
                .andExpect(jsonPath(itemReview("items[0].items", ITEM_WRITABLE) + ".hidden").isEmpty());
    }

    @Test
    @DisplayName("O2 주문 목록 품목 review: 상세와 같은 3종 상태")
    void orderList_itemReviewStatus() throws Exception {
        mockMvc.perform(get("/api/v1/orders").with(authHeaders.buyer(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(itemReview("items[0].items", ITEM_WRITABLE) + ".status").value("WRITABLE"))
                .andExpect(jsonPath(itemReview("items[0].items", ITEM_WRITTEN) + ".status").value("WRITTEN"))
                .andExpect(jsonPath(itemReview("items[0].items", ITEM_WRITTEN) + ".reviewId").value(writtenReviewPid))
                .andExpect(jsonPath(itemReview("items[0].items", ITEM_DELETED_REVIEW) + ".status").value("WRITTEN"))
                .andExpect(jsonPath(itemReview("items[0].items", ITEM_NOT_ELIGIBLE) + ".status").value("NOT_ELIGIBLE"));
    }

    @Test
    @DisplayName("O3 상품 목록 별점: 공개 리뷰만(숨김·삭제 제외) 평균 4.5·2개 / 리뷰 없는 상품 reviewCount 0·averageRating 키 없음")
    void productList_ratingFromVisibleReviews() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("sellerPublicId", sellerPid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.productPublicId == '" + productPid(PRODUCT) + "')].averageRating").value(4.5))
                .andExpect(jsonPath("$.items[?(@.productPublicId == '" + productPid(PRODUCT) + "')].reviewCount").value(2))
                .andExpect(jsonPath("$.items[?(@.productPublicId == '" + productPid(OTHER_PRODUCT) + "')].reviewCount").value(0))
                .andExpect(jsonPath("$.items[?(@.productPublicId == '" + productPid(OTHER_PRODUCT) + "')].averageRating").isEmpty());
    }

    @Test
    @DisplayName("O4 N+1 없음: 리뷰 달린 품목(한 주문 1→4개)·목록 상품(2→5개)·공개 리뷰(2→4개·키워드·사진)를 늘려도 각 조회의 쿼리 수가 같다")
    void queryCount_doesNotGrowWithRows() throws Exception {
        // 주문 목록에는 리뷰와 무관한 주문당 조회(배송지 스냅샷 1:1 비소유 측 로딩)가 이미 있어 주문 수를 늘리면 쿼리 수가 함께 는다.
        // 그래서 주문은 1건으로 고정하고 그 안의 리뷰 달린 품목 수를 늘린다(리뷰 상태를 품목마다 조회하면 여기서 늘어난다).
        // 공개 리뷰 목록은 키워드가 하나도 없으면 키워드 마스터 조회를 건너뛴다 — 비교 전후 모두 키워드·사진이 있게 맞춘다.
        fixture.selectKeyword(REVIEW_VISIBLE, "DELIVERY_FAST");
        fixture.selectKeyword(REVIEW_SECOND_VISIBLE, "DELIVERY_FAST");
        fixture.insertReviewPhoto(REVIEW_VISIBLE, REVIEW_VISIBLE, BUYER, "reviews/2026/09/RVL-" + REVIEW_VISIBLE + ".png", 0);
        fixture.seedUser(SECOND_BUYER);
        fixture.seedItem(SECOND_ORDER, SECOND_ORDER_FIRST_ITEM, SECOND_BUYER, PRODUCT, VARIANT, SELLER, "CONFIRMED", null);
        fixture.insertReview(SECOND_ORDER_FIRST_ITEM, SECOND_ORDER_FIRST_ITEM, OTHER_PRODUCT, SECOND_BUYER, 3, "VISIBLE", null, 0,
                LocalDateTime.now());
        long orderListOneItem = countQueries(() -> mockMvc.perform(get("/api/v1/orders").with(authHeaders.buyer(SECOND_BUYER)))
                .andExpect(status().isOk()));
        long orderDetailOneItem = countQueries(() -> mockMvc.perform(get(secondOrderUrl()).with(authHeaders.buyer(SECOND_BUYER)))
                .andExpect(status().isOk()));
        long productListTwo = countQueries(() -> mockMvc.perform(get("/api/v1/products").param("sellerPublicId", sellerPid))
                .andExpect(jsonPath("$.totalCount").value(2)));
        long reviewListTwo = countQueries(() -> mockMvc.perform(get(publicReviewsUrl()).with(authHeaders.buyer(SECOND_BUYER)))
                .andExpect(jsonPath("$.totalCount").value(2)));

        for (int index = 1; index <= GROWTH_ROWS; index++) {
            long itemId = SECOND_ORDER_FIRST_ITEM + index;
            fixture.seedItemInOrder(SECOND_ORDER, itemId, PRODUCT, VARIANT, SELLER, "CONFIRMED");
            fixture.insertReview(itemId, itemId, OTHER_PRODUCT, SECOND_BUYER, 3, "VISIBLE", null, 0, LocalDateTime.now());
            long base = GROWTH_BASE + (index - 1) * 2L;
            fixture.seedCatalog(SELLER + BAND_TO + index, base, base + 1, CATEGORY);
            fixture.withoutForeignKeys(() -> jdbc.update("UPDATE product SET seller_id = ? WHERE id = ?", SELLER, base));
        }
        for (long reviewId : new long[] {EXTRA_PUBLIC_REVIEW, EXTRA_PUBLIC_REVIEW + 1}) {
            fixture.insertReview(reviewId, reviewId, PRODUCT, BUYER, 4, "VISIBLE", null, 0, LocalDateTime.now());
            fixture.selectKeyword(reviewId, "QUALITY_GOOD");
            fixture.insertReviewPhoto(reviewId, reviewId, BUYER, "reviews/2026/09/RVL-" + reviewId + ".png", 0);
        }

        long orderListFourItems = countQueries(() -> mockMvc.perform(get("/api/v1/orders").with(authHeaders.buyer(SECOND_BUYER)))
                .andExpect(jsonPath("$.items[0].items.length()").value(1 + GROWTH_ROWS)));
        long orderDetailFourItems = countQueries(() -> mockMvc.perform(get(secondOrderUrl()).with(authHeaders.buyer(SECOND_BUYER)))
                .andExpect(jsonPath("$.sellers[0].items.length()").value(1 + GROWTH_ROWS)));
        long productListFive = countQueries(() -> mockMvc.perform(get("/api/v1/products").param("sellerPublicId", sellerPid))
                .andExpect(jsonPath("$.totalCount").value(2 + GROWTH_ROWS)));
        long reviewListFour = countQueries(() -> mockMvc.perform(get(publicReviewsUrl()).with(authHeaders.buyer(SECOND_BUYER)))
                .andExpect(jsonPath("$.totalCount").value(4)));

        assertThat(orderListFourItems).as("주문 목록 쿼리 수(품목 1 → 4)").isEqualTo(orderListOneItem);
        assertThat(orderDetailFourItems).as("주문 상세 쿼리 수(품목 1 → 4)").isEqualTo(orderDetailOneItem);
        assertThat(productListFive).as("상품 목록 쿼리 수(상품 2 → 5)").isEqualTo(productListTwo);
        assertThat(reviewListFour).as("공개 리뷰 목록 쿼리 수(리뷰 2 → 4)").isEqualTo(reviewListTwo);
    }

    // ---------- helpers ----------

    private interface Request {
        void perform() throws Exception;
    }

    private long countQueries(Request request) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            request.perform();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private static String itemReview(String itemsPath, long itemId) {
        return "$." + itemsPath + "[?(@.orderItemId == '" + ReviewFixture.pid("oit_", "RVI" + itemId) + "')].review";
    }

    private static String productPid(long productId) {
        return ReviewFixture.pid("prd_", "RVP" + productId);
    }

    private static String secondOrderUrl() {
        return "/api/v1/orders/" + ReviewFixture.pid("ord_", "RVO" + SECOND_ORDER);
    }

    private static String publicReviewsUrl() {
        return "/api/v1/products/" + productPid(PRODUCT) + "/reviews";
    }
}
