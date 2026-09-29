package com.zslab.mall.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 리뷰 공개 조회·사진 서빙 통합 테스트(Track 106-1 STEP 3·실 MariaDB·실 파일). 공개 = VISIBLE + 미삭제. 목록 필터·정렬·로그인 구매자의
 * "내가 누름"·요약 집계와, 사진 서빙의 공개 상태 인가(숨김·삭제·미연결·미존재 404)·캐시 금지·우회 경로(대소문자·인코딩·경로 탈출)를 검증한다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"zslab.attachment.cleanup.enabled=false"})
class ReviewPublicQueryIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10640L;
    private static final long BAND_TO = 10659L;
    private static final long BUYER = 10641L;
    private static final long SELLER = 10642L;
    private static final long PRODUCT = 10643L;
    private static final long VARIANT = 10644L;
    private static final long CATEGORY = 10645L;
    private static final long REVIEW_BLACK_OLD = 10646L;
    private static final long REVIEW_WHITE = 10647L;
    private static final long REVIEW_BLACK_NEW = 10648L;
    private static final long REVIEW_HIDDEN = 10649L;
    private static final long REVIEW_DELETED = 10650L;
    private static final long PHOTO_VISIBLE = 10651L;
    private static final long PHOTO_HIDDEN = 10652L;
    private static final long PHOTO_DELETED = 10653L;
    private static final long PHOTO_UNLINKED = 10654L;
    private static final long PHOTO_FILE_MISSING = 10655L;
    private static final String BLACK = "색상: 블랙";
    private static final String WHITE = "색상: 화이트";
    private static final String VISIBLE_KEY = "reviews/2026/09/RVQ-VISIBLE.png";
    private static final String HIDDEN_KEY = "reviews/2026/09/RVQ-HIDDEN.png";
    private static final String DELETED_KEY = "reviews/2026/09/RVQ-DELETED.png";
    private static final String UNLINKED_KEY = "reviews/2026/09/RVQ-UNLINKED.png";
    private static final String NO_ROW_KEY = "reviews/2026/09/RVQ-NOROW.png";
    private static final String FILE_MISSING_KEY = "reviews/2026/09/RVQ-MISSING.png";
    private static final String FILES = "/api/v1/files/";

    @TempDir
    static Path uploadRoot;

    @DynamicPropertySource
    static void uploadPath(DynamicPropertyRegistry registry) {
        registry.add("upload.path", () -> uploadRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private ReviewFixture fixture;
    private String productPid;
    private String blackOldPid;
    private String whitePid;
    private String blackNewPid;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new ReviewFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(BUYER);
        fixture.seedCatalog(SELLER, PRODUCT, VARIANT, CATEGORY);
        productPid = ReviewFixture.pid("prd_", "RVP" + PRODUCT);
        LocalDateTime now = LocalDateTime.now();
        // 품목 id는 리뷰 id와 같게 둔다(FK 끔·uk_review_order_item만 만족하면 된다).
        blackOldPid = fixture.insertReview(REVIEW_BLACK_OLD, REVIEW_BLACK_OLD, PRODUCT, BUYER, 5, "VISIBLE", BLACK, 3, now.minusDays(3));
        whitePid = fixture.insertReview(REVIEW_WHITE, REVIEW_WHITE, PRODUCT, BUYER, 4, "VISIBLE", WHITE, 10, now.minusDays(2));
        blackNewPid = fixture.insertReview(REVIEW_BLACK_NEW, REVIEW_BLACK_NEW, PRODUCT, BUYER, 2, "VISIBLE", BLACK, 0, now.minusDays(1));
        fixture.insertReview(REVIEW_HIDDEN, REVIEW_HIDDEN, PRODUCT, BUYER, 1, "HIDDEN", BLACK, 0, now);
        fixture.insertReview(REVIEW_DELETED, REVIEW_DELETED, PRODUCT, BUYER, 1, "VISIBLE", BLACK, 0, now);
        jdbc.update("UPDATE review SET deleted_at = NOW(6) WHERE id = ?", REVIEW_DELETED);
        fixture.selectKeyword(REVIEW_BLACK_OLD, "DELIVERY_FAST");
        fixture.selectKeyword(REVIEW_WHITE, "QUALITY_GOOD");
        fixture.selectKeyword(REVIEW_HIDDEN, "DELIVERY_FAST");

        fixture.insertReviewPhoto(PHOTO_VISIBLE, REVIEW_BLACK_OLD, BUYER, VISIBLE_KEY, 0);
        fixture.insertReviewPhoto(PHOTO_HIDDEN, REVIEW_HIDDEN, BUYER, HIDDEN_KEY, 0);
        fixture.insertReviewPhoto(PHOTO_DELETED, REVIEW_DELETED, BUYER, DELETED_KEY, 0);
        fixture.insertReviewPhoto(PHOTO_UNLINKED, REVIEW_BLACK_OLD, BUYER, UNLINKED_KEY, 1);
        jdbc.update("UPDATE attachment SET target_id = NULL WHERE id = ?", PHOTO_UNLINKED);
        fixture.insertReviewPhoto(PHOTO_FILE_MISSING, REVIEW_WHITE, BUYER, FILE_MISSING_KEY, 0);
        for (String key : new String[] {VISIBLE_KEY, HIDDEN_KEY, DELETED_KEY, UNLINKED_KEY, NO_ROW_KEY}) {
            writeStoredFile(key);
        }
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    // ==================== 목록 ====================

    @Test
    @DisplayName("Q1 비로그인 목록 → 200·숨김·삭제 제외 3건·기본 최신순·항목 키워드·사진·helpedByMe 키 없음")
    void list_anonymous_returnsVisibleOnlyLatestFirst() throws Exception {
        mockMvc.perform(get(listUrl()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items[0].reviewId").value(blackNewPid))
                .andExpect(jsonPath("$.items[1].reviewId").value(whitePid))
                .andExpect(jsonPath("$.items[2].reviewId").value(blackOldPid))
                .andExpect(jsonPath("$.items[2].keywords[0].code").value("DELIVERY_FAST"))
                .andExpect(jsonPath("$.items[2].keywords[0].label").value("배송이 빨라요"))
                .andExpect(jsonPath("$.items[2].photos.length()").value(1))
                .andExpect(jsonPath("$.items[2].photos[0].url").value(FILES + VISIBLE_KEY))
                .andExpect(jsonPath("$.items[2].optionLabel").value(BLACK))
                .andExpect(jsonPath("$.items[0].helpedByMe").doesNotExist());
    }

    @Test
    @DisplayName("Q2 필터·정렬: 키워드·옵션·사진만·도움순 / 알 수 없는 키워드·정렬 값 → 400")
    void list_filtersAndSort() throws Exception {
        mockMvc.perform(get(listUrl()).param("keyword", "DELIVERY_FAST"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].reviewId").value(blackOldPid));
        mockMvc.perform(get(listUrl()).param("option", BLACK))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[0].reviewId").value(blackNewPid))
                .andExpect(jsonPath("$.items[1].reviewId").value(blackOldPid));
        mockMvc.perform(get(listUrl()).param("photoOnly", "true"))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[0].reviewId").value(whitePid))
                .andExpect(jsonPath("$.items[1].reviewId").value(blackOldPid));
        mockMvc.perform(get(listUrl()).param("sort", "HELPFUL"))
                .andExpect(jsonPath("$.items[0].reviewId").value(whitePid))
                .andExpect(jsonPath("$.items[1].reviewId").value(blackOldPid))
                .andExpect(jsonPath("$.items[2].reviewId").value(blackNewPid));

        mockMvc.perform(get(listUrl()).param("keyword", "NO_SUCH_KEYWORD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(get(listUrl()).param("sort", "RANDOM"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("Q3 구매자 쿠키로 조회 → 항목마다 helpedByMe(누른 리뷰 true·나머지 false)")
    void list_withBuyerCookie_marksHelpedByMe() throws Exception {
        jdbc.update("INSERT INTO review_helpful (review_id, user_id, created_at) VALUES (?, ?, NOW(6))", REVIEW_WHITE, BUYER);

        mockMvc.perform(get(listUrl()).with(authHeaders.buyer(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].reviewId").value(whitePid))
                .andExpect(jsonPath("$.items[1].helpedByMe").value(true))
                .andExpect(jsonPath("$.items[0].helpedByMe").value(false));
    }

    @Test
    @DisplayName("Q4 요약: 공개 3건 평균 3.7·분포 5→1·키워드 집계·최근 사진(공개 리뷰 연결분만)·요약 없음(키 생략) / 없는 상품 404")
    void summary_aggregatesVisibleReviews() throws Exception {
        mockMvc.perform(get(listUrl() + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewCount").value(3))
                .andExpect(jsonPath("$.averageRating").value(3.7))
                .andExpect(jsonPath("$.ratingDistribution[0].rating").value(5))
                .andExpect(jsonPath("$.ratingDistribution[0].count").value(1))
                .andExpect(jsonPath("$.ratingDistribution[2].count").value(0))
                .andExpect(jsonPath("$.ratingDistribution[3].rating").value(2))
                .andExpect(jsonPath("$.ratingDistribution[3].count").value(1))
                .andExpect(jsonPath("$.keywords.length()").value(2))
                .andExpect(jsonPath("$.recentPhotos.length()").value(2))
                .andExpect(jsonPath("$.recentPhotos[0].reviewId").value(whitePid))
                .andExpect(jsonPath("$.recentPhotos[1].url").value(FILES + VISIBLE_KEY))
                .andExpect(jsonPath("$.summaryText").doesNotExist());

        mockMvc.perform(get("/api/v1/products/" + ReviewFixture.pid("prd_", "RVNOPRODUCT") + "/reviews/summary"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("Q9 비노출 상품(상세와 같은 판정): 상품 HIDDEN·판매자 SUSPENDED → 목록·요약 404 PRODUCT_NOT_FOUND / 판매중지(STOPPED) → 200")
    void nonDisplayableProduct_hidesReviews() throws Exception {
        fixture.withoutForeignKeys(() -> jdbc.update("UPDATE product SET status = 'HIDDEN' WHERE id = ?", PRODUCT));
        mockMvc.perform(get(listUrl())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(get(listUrl() + "/summary")).andExpect(status().isNotFound());

        fixture.withoutForeignKeys(() -> jdbc.update("UPDATE product SET status = 'STOPPED' WHERE id = ?", PRODUCT));
        mockMvc.perform(get(listUrl())).andExpect(status().isOk());

        fixture.withoutForeignKeys(() -> jdbc.update("UPDATE product SET status = 'SALE' WHERE id = ?", PRODUCT));
        fixture.withoutForeignKeys(() -> jdbc.update("UPDATE seller SET status = 'SUSPENDED' WHERE id = ?", SELLER));
        mockMvc.perform(get(listUrl())).andExpect(status().isNotFound());
        mockMvc.perform(get(listUrl() + "/summary")).andExpect(status().isNotFound());
    }

    // ==================== 사진 서빙 ====================

    @Test
    @DisplayName("Q5 사진 서빙: 공개 리뷰 사진 익명 200 · 숨김·삭제·미연결·행 없음·파일 없음 404 · 모두 Cache-Control no-store 단일 값 · nosniff")
    void photoServing_onlyVisibleReviewPhotos() throws Exception {
        mockMvc.perform(get(FILES + VISIBLE_KEY))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        for (String key : new String[] {HIDDEN_KEY, DELETED_KEY, UNLINKED_KEY, NO_ROW_KEY, FILE_MISSING_KEY}) {
            mockMvc.perform(get(FILES + key))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"))
                    .andExpect(header().stringValues("Cache-Control", "no-store"));
        }
        // 작성자 본인 쿠키도 숨김 사진은 열 수 없다(공개 상태만 본다).
        mockMvc.perform(get(FILES + HIDDEN_KEY).with(authHeaders.buyer(BUYER))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Q6 우회 경로: 대소문자 변형(Reviews/·REVIEWS/)·인코딩 접두(%72eviews/)로 숨김 사진 → 404")
    void photoServing_caseAndEncodingVariants_notFound() throws Exception {
        String hiddenRest = HIDDEN_KEY.substring("reviews/".length());

        mockMvc.perform(get(FILES + "Reviews/" + hiddenRest)).andExpect(status().isNotFound());
        mockMvc.perform(get(FILES + "REVIEWS/" + hiddenRest)).andExpect(status().isNotFound());
        mockMvc.perform(get(URI.create(FILES + "%72eviews/" + hiddenRest))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Q7 경로 탈출: products/../reviews/(숨김)·%2e%2e 인코딩 → 200 아님(4xx)")
    void photoServing_traversal_rejected() throws Exception {
        String hiddenRest = HIDDEN_KEY.substring("reviews/".length());

        int plain = mockMvc.perform(get(URI.create(FILES + "products/../reviews/" + hiddenRest))).andReturn().getResponse().getStatus();
        int encoded = mockMvc.perform(get(URI.create(FILES + "products/%2e%2e/reviews/" + hiddenRest)))
                .andReturn().getResponse().getStatus();

        assertThat(plain).isBetween(400, 499);
        assertThat(encoded).isBetween(400, 499);
    }

    @Test
    @DisplayName("Q8 클레임 첨부·상품 이미지 경로 불변: 클레임 키 익명 401 · 상품 이미지 익명 200 public immutable")
    void otherPrefixes_unchanged() throws Exception {
        writeStoredFile("products/2026/09/RVQ-PRODUCT.png");

        mockMvc.perform(get(FILES + "claims/2026/09/RVQ-CLAIM.png")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(FILES + "products/2026/09/RVQ-PRODUCT.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
    }

    // ==================== helpers ====================

    private String listUrl() {
        return "/api/v1/products/" + productPid + "/reviews";
    }

    private void writeStoredFile(String relativeKey) throws IOException {
        Path target = uploadRoot.resolve(relativeKey);
        Files.createDirectories(target.getParent());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", output);
        Files.write(target, output.toByteArray());
    }
}
