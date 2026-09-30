package com.zslab.mall.productquestion;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
 * 공개 질문 목록·즉시 답 통합 테스트(Track 106-2·실 MariaDB). 목록은 VISIBLE만(미답변 포함)·숨김·삭제 제외, 즉시 답은 토큰 일치(끝 1자 제거 포함)·
 * 점수 순·숨김·삭제된 질문과 리뷰 제외·비노출 상품 404·질의 길이 400을 검증한다(D-239 규칙 8 · 즉시 답).
 */
@AutoConfigureMockMvc
class ProductQuestionPublicQueryIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10860L;
    private static final long BAND_TO = 10879L;
    private static final long BUYER = 10861L;
    private static final long OTHER_BUYER = 10862L;
    private static final long SELLER = 10863L;
    private static final long SELLER_USER = 10864L;
    private static final long PRODUCT = 10865L;
    private static final long SUSPENDED_SELLER = 10866L;
    private static final long SUSPENDED_PRODUCT = 10867L;
    private static final long QUESTION_ANSWERED = 10868L;
    private static final long QUESTION_OPEN = 10869L;
    private static final long QUESTION_HIDDEN = 10870L;
    private static final long QUESTION_DELETED = 10871L;
    private static final long REVIEW_VISIBLE = 10872L;
    private static final long REVIEW_HIDDEN = 10873L;
    private static final long REVIEW_DELETED = 10874L;
    private static final long PRODUCT_MANY_FRAGMENTS = 10875L;
    private static final long PRODUCT_LONG_DESCRIPTION = 10876L;
    private static final long QUESTION_WATERPROOF = 10877L;
    private static final long REVIEW_WATERPROOF = 10878L;
    // 동점 유형 우선 테스트: 상품 id는 대역 안 · 질문·리뷰 id는 테이블이 달라 상품 id와 겹쳐도 된다(이 클래스의 질문·리뷰 id와는 겹치지 않는다).
    private static final long PRODUCT_TIE = 10879L;
    private static final long QUESTION_TIE = 10876L;
    private static final long REVIEW_TIE = 10879L;
    /** 서비스의 설명 조각 상한(ProductQuestionSuggestService.FRAGMENT_LIMIT)과 같은 값. */
    private static final int FRAGMENT_LIMIT = 200;
    private static final String DESCRIPTION = "면 100% 소재입니다.\n세탁기 사용이 가능합니다. 건조기는 피해 주세요.";

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
    private String suspendedProductPid;
    private String answeredPid;
    private String openPid;
    private String reviewPid;

    @BeforeEach
    void setUp() {
        fixture = new ProductQuestionFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(BUYER);
        fixture.seedUser(OTHER_BUYER);
        productPid = fixture.seedCatalog(SELLER, "ACTIVE", PRODUCT, DESCRIPTION);
        suspendedProductPid = fixture.seedCatalog(SUSPENDED_SELLER, "SUSPENDED", SUSPENDED_PRODUCT, DESCRIPTION);
        fixture.seedSellerUser(SELLER_USER, SELLER);
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        answeredPid = fixture.insertQuestion(QUESTION_ANSWERED, PRODUCT, BUYER, "세탁 방법이 궁금합니다", "VISIBLE", "찬물 단독 세탁을 권합니다",
                SELLER_USER, base);
        openPid = fixture.insertQuestion(QUESTION_OPEN, PRODUCT, OTHER_BUYER, "재입고 예정이 있나요", "VISIBLE", null, null,
                base.plusHours(1));
        fixture.insertQuestion(QUESTION_HIDDEN, PRODUCT, BUYER, "숨김된 세탁 질문", "HIDDEN", "숨김 답변 세탁", SELLER_USER,
                base.plusHours(2));
        fixture.insertQuestion(QUESTION_DELETED, PRODUCT, BUYER, "삭제된 세탁 질문", "VISIBLE", "삭제 답변 세탁", SELLER_USER,
                base.plusHours(3));
        fixture.markQuestionDeleted(QUESTION_DELETED);
        reviewPid = fixture.insertReview(REVIEW_VISIBLE, PRODUCT, BUYER, "세탁해도 줄어들지 않아요", "VISIBLE", base.plusHours(4));
        fixture.insertReview(REVIEW_HIDDEN, PRODUCT, BUYER, "숨김 리뷰 세탁 이야기", "HIDDEN", base.plusHours(5));
        fixture.insertReview(REVIEW_DELETED, PRODUCT, BUYER, "삭제 리뷰 세탁 이야기", "VISIBLE", base.plusHours(6));
        fixture.markReviewDeleted(REVIEW_DELETED);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("P1 공개 목록: VISIBLE만(미답변 포함)·숨김·삭제 제외·최신순·답변 포함 / 익명 writtenByMe 없음·구매자면 값 / 작성자 필드 없음")
    void publicList_showsVisibleOnly() throws Exception {
        mockMvc.perform(get(listUrl(productPid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[0].questionId").value(openPid))
                .andExpect(jsonPath("$.items[0].answerContent").doesNotExist())
                .andExpect(jsonPath("$.items[1].questionId").value(answeredPid))
                .andExpect(jsonPath("$.items[1].answerContent").value("찬물 단독 세탁을 권합니다"))
                .andExpect(jsonPath("$.items[1].answeredAt").exists())
                .andExpect(jsonPath("$.items[0].writtenByMe").doesNotExist())
                .andExpect(jsonPath("$.items[0].buyerId").doesNotExist());

        mockMvc.perform(get(listUrl(productPid)).with(authHeaders.buyer(BUYER)))
                .andExpect(jsonPath("$.items[0].writtenByMe").value(false))
                .andExpect(jsonPath("$.items[1].writtenByMe").value(true));
        mockMvc.perform(get(listUrl(suspendedProductPid))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("P2 즉시 답: \"세탁이 가능한 제품인가요?\" → 끝 1자 뗀 \"세탁\"·\"가능\"으로 일치 · 점수 순(2점 PRODUCT → 1점 QNA → REVIEW) · 숨김·삭제 질문·리뷰 제외 · QNA는 답변 포함")
    void suggest_matchesStemmedTokensAndExcludesHiddenOrDeleted() throws Exception {
        mockMvc.perform(get(suggestUrl(productPid)).param("q", "세탁이 가능한 제품인가요?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.type == 'QNA')].id").value(answeredPid))
                .andExpect(jsonPath("$[?(@.type == 'QNA')].answer").value("찬물 단독 세탁을 권합니다"))
                .andExpect(jsonPath("$[?(@.type == 'REVIEW')].id").value(reviewPid))
                .andExpect(jsonPath("$[?(@.type == 'PRODUCT')].text").value("세탁기 사용이 가능합니다."))
                .andExpect(jsonPath("$[?(@.type == 'PRODUCT')].id").doesNotExist())
                .andExpect(jsonPath("$[?(@.text =~ /.*숨김.*/)]").isEmpty())
                .andExpect(jsonPath("$[?(@.text =~ /.*삭제.*/)]").isEmpty())
                // "세탁기 사용이 가능합니다."만 두 토큰(세탁·가능)이 일치해 점수 2로 맨 앞이고, 1점 동점은 유형 우선(Q&A → 리뷰)으로 뒤따른다.
                .andExpect(jsonPath("$[0].type").value("PRODUCT"))
                .andExpect(jsonPath("$[1].type").value("QNA"))
                .andExpect(jsonPath("$[2].type").value("REVIEW"));
    }

    @Test
    @DisplayName("P3 즉시 답 경계: 일치 0건 → 빈 배열 / 2자 미만 토큰뿐 → 빈 배열 / q trim 후 1자·101자·누락 400 / 비노출 상품 404")
    void suggest_boundaries() throws Exception {
        mockMvc.perform(get(suggestUrl(productPid)).param("q", "배터리 용량")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(suggestUrl(productPid)).param("q", "a b c")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(suggestUrl(productPid)).param("q", "  세  ")).andExpect(status().isBadRequest());
        mockMvc.perform(get(suggestUrl(productPid)).param("q", "가".repeat(101))).andExpect(status().isBadRequest());
        mockMvc.perform(get(suggestUrl(productPid))).andExpect(status().isBadRequest());
        mockMvc.perform(get(suggestUrl(suspendedProductPid)).param("q", "세탁 방법")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("P4 결과 유형 상한: 일치 설명 조각 6개(상품 수정 시각으로 동점 최신) + Q&A 1 + 리뷰 1 → 5건 중 PRODUCT 3건 · Q&A·리뷰 포함")
    void suggest_capsProductFragments() throws Exception {
        String description = IntStream.rangeClosed(1, 6).mapToObj(index -> "방수 안내 " + index + "번입니다.").collect(Collectors.joining("\n"));
        String productPublicId = fixture.seedProduct(PRODUCT_MANY_FRAGMENTS, SELLER, "SALE", description);
        LocalDateTime earlier = LocalDateTime.now().minusDays(2);
        String questionPid = fixture.insertQuestion(QUESTION_WATERPROOF, PRODUCT_MANY_FRAGMENTS, BUYER, "방수가 되는 제품인가요", "VISIBLE",
                "생활 방수입니다", SELLER_USER, earlier);
        String waterproofReviewPid = fixture.insertReview(REVIEW_WATERPROOF, PRODUCT_MANY_FRAGMENTS, BUYER, "비 오는 날 방수 잘 돼요", "VISIBLE",
                earlier);

        // 상한이 없으면 1점 동점에서 더 최신인 설명 조각 5개가 결과를 모두 차지한다.
        mockMvc.perform(get(suggestUrl(productPublicId)).param("q", "방수"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[?(@.type == 'PRODUCT')]", hasSize(3)))
                .andExpect(jsonPath("$[?(@.type == 'QNA')].id").value(questionPid))
                .andExpect(jsonPath("$[?(@.type == 'REVIEW')].id").value(waterproofReviewPid));
    }

    @Test
    @DisplayName("P6 동점 유형 우선: 1점 동점 QNA·REVIEW·PRODUCT(상품 수정 시각이 가장 늦어 최신순이면 PRODUCT가 1순위) → QNA → REVIEW → PRODUCT")
    void suggest_tieBreaksByTypeBeforeRecency() throws Exception {
        String productPublicId = fixture.seedProduct(PRODUCT_TIE, SELLER, "SALE", "방수 원단입니다.");
        LocalDateTime earlier = LocalDateTime.now().minusDays(2);
        String questionPid = fixture.insertQuestion(QUESTION_TIE, PRODUCT_TIE, BUYER, "방수가 되는 제품인가요", "VISIBLE", "생활 방수입니다",
                SELLER_USER, earlier);
        String tieReviewPid = fixture.insertReview(REVIEW_TIE, PRODUCT_TIE, BUYER, "비 오는 날 방수 잘 돼요", "VISIBLE", earlier.plusHours(1));

        mockMvc.perform(get(suggestUrl(productPublicId)).param("q", "방수"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].type").value("QNA"))
                .andExpect(jsonPath("$[0].id").value(questionPid))
                .andExpect(jsonPath("$[1].type").value("REVIEW"))
                .andExpect(jsonPath("$[1].id").value(tieReviewPid))
                .andExpect(jsonPath("$[2].type").value("PRODUCT"));
    }

    @Test
    @DisplayName("P5 설명 조각 상한 200: 200번째 조각만 일치 → 1건(대조) / 201번째 이후 조각만 일치 → 0건")
    void suggest_ignoresFragmentsBeyondLimit() throws Exception {
        String description = IntStream.rangeClosed(1, FRAGMENT_LIMIT - 1).mapToObj(index -> "기본 안내 " + index + "번.")
                .collect(Collectors.joining("\n")) + "\n마지막조각 안내.\n초과조각 안내.\n초과조각 추가 안내.";
        String productPublicId = fixture.seedProduct(PRODUCT_LONG_DESCRIPTION, SELLER, "SALE", description);

        mockMvc.perform(get(suggestUrl(productPublicId)).param("q", "마지막조각"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].text").value("마지막조각 안내."));
        mockMvc.perform(get(suggestUrl(productPublicId)).param("q", "초과조각"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private static String listUrl(String productPublicId) {
        return "/api/v1/products/" + productPublicId + "/questions";
    }

    private static String suggestUrl(String productPublicId) {
        return listUrl(productPublicId) + "/suggest";
    }
}
