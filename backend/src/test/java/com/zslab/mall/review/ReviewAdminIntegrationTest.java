package com.zslab.mall.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 관리자 리뷰 숨김 통합 테스트(Track 106-1 STEP 6·실 MariaDB·실 파일). 관리자 전용 인가·사유 필수·숨김 시 공개 목록·요약·사진에서 제외·해제 시
 * 복귀·같은 상태 422·감사 행(UPDATE·REVIEW·{status}/{status, reason})을 검증한다.
 *
 * <p>비관리자 판정: 역할 쿠키는 경로 접두사에 맞는 것만 읽으므로(D-235) 구매자 쿠키로 관리자 경로를 호출하면 익명과 같은 401이다. 역할 가드가
 * 실제로 걸렸는지는 같은 요청을 관리자 쿠키로 보냈을 때 200/204가 되는 것으로 대조한다(엔드포인트 부재 false-green 방지).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"zslab.attachment.cleanup.enabled=false"})
class ReviewAdminIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10700L;
    private static final long BAND_TO = 10719L;
    private static final long ADMIN = 10701L;
    private static final long BUYER = 10702L;
    private static final long SELLER = 10703L;
    private static final long PRODUCT = 10704L;
    private static final long VARIANT = 10705L;
    private static final long CATEGORY = 10706L;
    private static final long REVIEW = 10707L;
    private static final long OTHER_REVIEW = 10708L;
    private static final long PHOTO = 10709L;
    private static final String PHOTO_KEY = "reviews/2026/09/RVA-PHOTO.png";
    private static final String ADMIN_URL = "/api/v1/admin/reviews";

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
    private String reviewPid;
    private String productPid;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new ReviewFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(ADMIN);
        fixture.seedUser(BUYER);
        fixture.seedCatalog(SELLER, PRODUCT, VARIANT, CATEGORY);
        productPid = ReviewFixture.pid("prd_", "RVP" + PRODUCT);
        reviewPid = fixture.insertReview(REVIEW, REVIEW, PRODUCT, BUYER, 1, "VISIBLE", null, 0, LocalDateTime.now().minusHours(1));
        fixture.insertReview(OTHER_REVIEW, OTHER_REVIEW, PRODUCT, BUYER, 5, "VISIBLE", null, 0, LocalDateTime.now().minusHours(2));
        fixture.insertReviewPhoto(PHOTO, REVIEW, BUYER, PHOTO_KEY, 0);
        Path target = uploadRoot.resolve(PHOTO_KEY);
        Files.createDirectories(target.getParent());
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", image);
        Files.write(target, image.toByteArray());
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("A1 인가: 익명·구매자 쿠키 → 목록·숨김 401 / 같은 요청 관리자 → 200·204 · 목록 항목에 상품·사진")
    void adminEndpoints_requireAdmin() throws Exception {
        mockMvc.perform(get(ADMIN_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ADMIN_URL).with(authHeaders.buyer(BUYER))).andExpect(status().isUnauthorized());
        mockMvc.perform(statusRequest("HIDDEN", "욕설").with(authHeaders.buyer(BUYER))).andExpect(status().isUnauthorized());
        assertThat(reviewStatus()).isEqualTo("VISIBLE");

        mockMvc.perform(get(ADMIN_URL).with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reviewId").value(reviewPid))
                .andExpect(jsonPath("$.items[0].productPublicId").value(productPid))
                .andExpect(jsonPath("$.items[0].photoUrls[0]").value("/api/v1/files/" + PHOTO_KEY));
        mockMvc.perform(statusRequest("HIDDEN", "욕설").with(authHeaders.admin(ADMIN))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("A2 형식: 사유 없음·공백 → 400 VALIDATION_FAILED / 허용 밖 상태 → 400 / 목록 status 오값 → 400 · 상태 불변")
    void changeStatus_invalidRequest_returns400() throws Exception {
        mockMvc.perform(patch(ADMIN_URL + "/" + reviewPid + "/status").with(authHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(statusRequest("HIDDEN", "  ").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(statusRequest("DELETED", "사유").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get(ADMIN_URL).param("status", "GONE").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isBadRequest());

        assertThat(reviewStatus()).isEqualTo("VISIBLE");
    }

    @Test
    @DisplayName("A3 숨김 → 공개 목록·요약·사진 제외·감사 1행 / 같은 상태 재요청 422 / 해제 → 복귀·감사 2행 / 관리자 목록 상태 필터")
    void hideAndUnhide_affectsPublicViewsAndAudits() throws Exception {
        mockMvc.perform(statusRequest("HIDDEN", "  광고성 게시물  ").with(authHeaders.admin(ADMIN))).andExpect(status().isNoContent());

        assertThat(reviewStatus()).isEqualTo("HIDDEN");
        assertThat(hiddenReason()).as("숨김 사유는 앞뒤 공백을 잘라 리뷰 행에 남는다").isEqualTo("광고성 게시물");
        mockMvc.perform(get(publicListUrl())).andExpect(jsonPath("$.totalCount").value(1));
        mockMvc.perform(get(publicListUrl() + "/summary")).andExpect(jsonPath("$.reviewCount").value(1));
        mockMvc.perform(get("/api/v1/files/" + PHOTO_KEY)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForMap("SELECT action, actor_user_id, diff_json FROM audit_log WHERE target_type = 'REVIEW' AND target_id = ?",
                REVIEW))
                .containsEntry("action", "UPDATE")
                .containsEntry("actor_user_id", ADMIN);
        String diff = jdbc.queryForObject("SELECT diff_json FROM audit_log WHERE target_type = 'REVIEW' AND target_id = ?", String.class,
                REVIEW);
        assertThat(diff).contains("VISIBLE").contains("HIDDEN").contains("광고성 게시물").doesNotContain("테스트 리뷰 본문");
        mockMvc.perform(get(ADMIN_URL).param("status", "HIDDEN").with(authHeaders.admin(ADMIN)))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].status").value("HIDDEN"));

        mockMvc.perform(statusRequest("HIDDEN", "다시").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_INVALID_STATE"));

        mockMvc.perform(statusRequest("VISIBLE", "오판 정정").with(authHeaders.admin(ADMIN))).andExpect(status().isNoContent());
        assertThat(reviewStatus()).isEqualTo("VISIBLE");
        assertThat(hiddenReason()).as("숨김 해제 시 사유를 비운다").isNull();
        mockMvc.perform(get(publicListUrl())).andExpect(jsonPath("$.totalCount").value(2));
        mockMvc.perform(get("/api/v1/files/" + PHOTO_KEY)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'REVIEW' AND target_id = ?", Integer.class,
                REVIEW)).isEqualTo(2);
    }

    @Test
    @DisplayName("A4 DB 제약: HIDDEN + 빈·공백 사유 UPDATE → chk_review_hidden_reason 위반 / 같은 UPDATE를 실제 사유로 → 통과(대조)")
    void hiddenWithBlankReason_rejectedBySchema() {
        String hide = "UPDATE review SET status = 'HIDDEN', hidden_reason = ? WHERE id = ?";

        assertThatThrownBy(() -> jdbc.update(hide, "", REVIEW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_review_hidden_reason");
        assertThatThrownBy(() -> jdbc.update(hide, "   ", REVIEW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_review_hidden_reason");
        assertThat(reviewStatus()).isEqualTo("VISIBLE");

        assertThat(jdbc.update(hide, "욕설", REVIEW)).isEqualTo(1);
        assertThat(reviewStatus()).isEqualTo("HIDDEN");
    }

    // ---------- helpers ----------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder statusRequest(String status, String reason) {
        return patch(ADMIN_URL + "/" + reviewPid + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\",\"reason\":\"" + reason + "\"}");
    }

    private String publicListUrl() {
        return "/api/v1/products/" + productPid + "/reviews";
    }

    private String hiddenReason() {
        return jdbc.queryForObject("SELECT hidden_reason FROM review WHERE id = ?", String.class, REVIEW);
    }

    private String reviewStatus() {
        return jdbc.queryForObject("SELECT status FROM review WHERE id = ?", String.class, REVIEW);
    }
}
