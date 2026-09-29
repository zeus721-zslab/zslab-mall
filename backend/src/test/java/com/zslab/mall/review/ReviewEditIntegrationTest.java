package com.zslab.mall.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 리뷰 수정·삭제 통합 테스트(Track 106-1 STEP 4·실 MariaDB·실 파일). 작성자만·전체 교체(빠진 사진은 연결 해제 → 서빙 404)·soft delete 후
 * 목록 제외·사진 404·재작성 409를 HTTP 흐름(업로드 → 작성 → 수정/삭제)으로 검증한다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"zslab.attachment.cleanup.enabled=false"})
class ReviewEditIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10660L;
    private static final long BAND_TO = 10679L;
    private static final long BUYER = 10661L;
    private static final long OTHER_BUYER = 10662L;
    private static final long SELLER = 10663L;
    private static final long PRODUCT = 10664L;
    private static final long VARIANT = 10665L;
    private static final long CATEGORY = 10666L;
    private static final long ORDER = 10667L;
    private static final long ITEM = 10668L;
    private static final String REVIEWS_URL = "/api/v1/reviews";

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

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private ReviewFixture fixture;
    private String itemPid;
    private String productPid;

    @BeforeEach
    void setUp() {
        fixture = new ReviewFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(BUYER);
        fixture.seedUser(OTHER_BUYER);
        fixture.seedCatalog(SELLER, PRODUCT, VARIANT, CATEGORY);
        itemPid = fixture.seedItem(ORDER, ITEM, BUYER, PRODUCT, VARIANT, SELLER, "CONFIRMED", "사이즈: M");
        productPid = ReviewFixture.pid("prd_", "RVP" + PRODUCT);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("E1 인증 없음 → 수정·삭제 401 / 작성자 외 구매자 → 수정·삭제 404 REVIEW_NOT_FOUND · 리뷰 불변")
    void editDelete_byOthers_rejected() throws Exception {
        String reviewId = createReview(List.of("DELIVERY_FAST"));

        mockMvc.perform(put(REVIEWS_URL + "/" + reviewId).contentType(MediaType.APPLICATION_JSON).content(updateBody(1, List.of(), List.of())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(REVIEWS_URL + "/" + reviewId)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(REVIEWS_URL + "/" + reviewId).with(authHeaders.buyer(OTHER_BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(1, List.of(), List.of())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
        mockMvc.perform(delete(REVIEWS_URL + "/" + reviewId).with(authHeaders.buyer(OTHER_BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));

        assertThat(jdbc.queryForMap("SELECT rating, deleted_at FROM review WHERE public_id = ?", reviewId))
                .containsEntry("rating", 5)
                .containsEntry("deleted_at", null);
    }

    @Test
    @DisplayName("E2 수정 전체 교체: 별점·본문·키워드 교체 · 사진 [A,B] → [B,C] → A 연결 해제·서빙 404 / B 순서 0·C 순서 1·서빙 200")
    void update_replacesEverythingAndUnlinksDroppedPhotos() throws Exception {
        String photoA = upload("a.png");
        String photoB = upload("b.png");
        String reviewId = createReview(List.of("DELIVERY_FAST"), photoA, photoB);
        String photoC = upload("c.png");

        mockMvc.perform(put(REVIEWS_URL + "/" + reviewId).with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(2, List.of("QUALITY_GOOD", "VALUE_FOR_MONEY"), List.of(photoB, photoC))))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForMap("SELECT rating, content FROM review WHERE public_id = ?", reviewId))
                .containsEntry("rating", 2)
                .containsEntry("content", "고친 리뷰");
        assertThat(jdbc.queryForList("SELECT k.code FROM review_keyword_selection s JOIN review_keyword k ON k.id = s.keyword_id "
                + "JOIN review r ON r.id = s.review_id WHERE r.public_id = ? ORDER BY k.code", String.class, reviewId))
                .containsExactly("QUALITY_GOOD", "VALUE_FOR_MONEY");
        assertThat(targetIdOf(photoA)).isNull();
        assertThat(displayOrderOf(photoB)).isZero();
        assertThat(displayOrderOf(photoC)).isEqualTo(1);
        mockMvc.perform(get(urlOf(photoA))).andExpect(status().isNotFound());
        mockMvc.perform(get(urlOf(photoB))).andExpect(status().isOk());
        mockMvc.perform(get(urlOf(photoC))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("E3 삭제 → 204 · 공개 목록 제외 · 사진 서빙 404 · 같은 품목 재작성 409 · 다시 삭제 404 · 행은 soft delete로 남음")
    void delete_softDeletesAndBlocksRewrite() throws Exception {
        String photo = upload("d.png");
        String reviewId = createReview(List.of(), photo);
        mockMvc.perform(get(urlOf(photo))).andExpect(status().isOk());

        mockMvc.perform(delete(REVIEWS_URL + "/" + reviewId).with(authHeaders.buyer(BUYER))).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/products/" + productPid + "/reviews")).andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(urlOf(photo))).andExpect(status().isNotFound());
        mockMvc.perform(post(REVIEWS_URL).with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(List.of())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_EXISTS"));
        mockMvc.perform(delete(REVIEWS_URL + "/" + reviewId).with(authHeaders.buyer(BUYER))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review WHERE public_id = ? AND deleted_at IS NOT NULL", Integer.class,
                reviewId)).isEqualTo(1);
    }

    @Test
    @DisplayName("E4 숨김 리뷰: 작성자 수정 → 422 REVIEW_INVALID_STATE·본문 불변(숨김 근거 원문 보존) / 작성자 삭제 → 204(soft delete로 행 보존)")
    void hiddenReview_editRejectedButDeleteAllowed() throws Exception {
        String reviewId = createReview(List.of());
        jdbc.update("UPDATE review SET status = 'HIDDEN', hidden_reason = '욕설' WHERE public_id = ?", reviewId);

        mockMvc.perform(put(REVIEWS_URL + "/" + reviewId).with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(1, List.of(), List.of())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_INVALID_STATE"));
        assertThat(jdbc.queryForMap("SELECT rating, content FROM review WHERE public_id = ?", reviewId))
                .containsEntry("rating", 5)
                .containsEntry("content", "처음 리뷰");

        mockMvc.perform(delete(REVIEWS_URL + "/" + reviewId).with(authHeaders.buyer(BUYER))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT content FROM review WHERE public_id = ? AND deleted_at IS NOT NULL", String.class,
                reviewId)).isEqualTo("처음 리뷰");
    }

    @Test
    @DisplayName("E5 작성자 단건 조회: 본인 200(별점·키워드·본문·사진 attachmentId·옵션·상태) · 타인 404 · 비로그인 401 · 숨김 → 상태·사유·photosPublic=false · 삭제 → 404")
    void getOwn_authorOnlyWithHiddenState() throws Exception {
        String photo = upload("own.png");
        String reviewId = createReview(List.of("DELIVERY_FAST"), photo);
        String ownUrl = REVIEWS_URL + "/" + reviewId;

        mockMvc.perform(get(ownUrl).with(authHeaders.buyer(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(reviewId))
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.content").value("처음 리뷰"))
                .andExpect(jsonPath("$.optionLabel").value("사이즈: M"))
                .andExpect(jsonPath("$.keywords[0].code").value("DELIVERY_FAST"))
                .andExpect(jsonPath("$.photos[0].attachmentId").value(photo))
                .andExpect(jsonPath("$.photos[0].url").value(urlOf(photo)))
                .andExpect(jsonPath("$.status").value("VISIBLE"))
                .andExpect(jsonPath("$.photosPublic").value(true))
                .andExpect(jsonPath("$.hiddenReason").doesNotExist());
        mockMvc.perform(get(ownUrl).with(authHeaders.buyer(OTHER_BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
        mockMvc.perform(get(ownUrl)).andExpect(status().isUnauthorized());

        jdbc.update("UPDATE review SET status = 'HIDDEN', hidden_reason = '광고성 게시물' WHERE public_id = ?", reviewId);
        mockMvc.perform(get(ownUrl).with(authHeaders.buyer(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HIDDEN"))
                .andExpect(jsonPath("$.hiddenReason").value("광고성 게시물"))
                .andExpect(jsonPath("$.photosPublic").value(false));
        mockMvc.perform(get(urlOf(photo)).with(authHeaders.buyer(BUYER))).andExpect(status().isNotFound());

        mockMvc.perform(delete(ownUrl).with(authHeaders.buyer(BUYER))).andExpect(status().isNoContent());
        mockMvc.perform(get(ownUrl).with(authHeaders.buyer(BUYER))).andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    private String createReview(List<String> keywordCodes, String... attachmentIds) throws Exception {
        String body = mockMvc.perform(post(REVIEWS_URL).with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(keywordCodes, attachmentIds)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return jsonMapper.readTree(body).get("reviewId").asText();
    }

    private String upload(String fileName) throws Exception {
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(6, 6, BufferedImage.TYPE_INT_RGB), "png", image);
        String body = mockMvc.perform(multipart(REVIEWS_URL + "/attachments")
                        .file(new MockMultipartFile("files", fileName, "image/png", image.toByteArray()))
                        .with(authHeaders.buyer(BUYER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode item = jsonMapper.readTree(body).get("results").get(0);
        return item.get("attachmentId").asText();
    }

    private String createBody(List<String> keywordCodes, String... attachmentIds) {
        return "{\"orderItemId\":\"" + itemPid + "\",\"rating\":5,\"content\":\"처음 리뷰\",\"keywordCodes\":" + jsonArray(keywordCodes)
                + ",\"attachmentIds\":" + jsonArray(Arrays.asList(attachmentIds)) + "}";
    }

    private static String updateBody(int rating, List<String> keywordCodes, List<String> attachmentIds) {
        return "{\"rating\":" + rating + ",\"content\":\"고친 리뷰\",\"keywordCodes\":" + jsonArray(keywordCodes)
                + ",\"attachmentIds\":" + jsonArray(attachmentIds) + "}";
    }

    private static String jsonArray(List<String> values) {
        return "[" + String.join(",", values.stream().map(value -> "\"" + value + "\"").toList()) + "]";
    }

    private String urlOf(String attachmentPublicId) {
        return jdbc.queryForObject("SELECT file_path FROM attachment WHERE public_id = ?", String.class, attachmentPublicId);
    }

    private Long targetIdOf(String attachmentPublicId) {
        return jdbc.queryForObject("SELECT target_id FROM attachment WHERE public_id = ?", Long.class, attachmentPublicId);
    }

    private Integer displayOrderOf(String attachmentPublicId) {
        return jdbc.queryForObject("SELECT display_order FROM attachment WHERE public_id = ?", Integer.class, attachmentPublicId);
    }
}
