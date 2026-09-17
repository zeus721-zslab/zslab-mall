package com.zslab.mall.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이미지 업로드·서빙·썸네일 동기화 E2E 통합 테스트(Track 77·실 MariaDB·임시 업로드 루트). {@code upload.path}를 JUnit @TempDir로
 * 덮어써 실제 업로드 경로를 오염시키지 않는다. multipart 컨테이너 한도(413)는 MockMvc가 서블릿 파서를 거치지 않아 검증 대상이 아니며,
 * 앱 단 파일별 크기 검증(FILE_TOO_LARGE)으로 대신 확인한다.
 */
@AutoConfigureMockMvc
class FileUploadServingIntegrationTest extends AbstractIntegrationTest {

    private static final String UPLOAD_URL = "/api/v1/admin/files/images";
    private static final long ADMIN_ID = 77000L;
    private static final long BUYER_ID = 77001L;
    private static final long SELLER_ID = 77001L;
    private static final long CATEGORY_ID = 77001L;
    private static final long PRODUCT_ID = 77001L;
    private static final String PRODUCT_PID = "prd_" + ("T77PRODUCT" + "00000000000000000000000000").substring(0, 26);
    private static final int THUMBNAIL_WIDTH = 400;
    private static final long MAX_FILE_SIZE = 10_485_760L;
    /** 1x1 lossless WebP(VP8L) 최소 바이트 — JDK ImageIO는 webp를 쓰지 못하므로 고정 픽스처로 둔다. */
    private static final byte[] WEBP_1X1 = {
            'R', 'I', 'F', 'F', 0x1A, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', 'L',
            0x0D, 0, 0, 0, 0x2F, 0, 0, 0, 0x10, 0x07, 0x10, 0x11, 0x11, (byte) 0x88, (byte) 0x88, (byte) 0xFE, 0x07, 0};

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
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() throws IOException {
        tx = new TransactionTemplate(txManager);
        cleanup();
        clearUploadRoot(); // static @TempDir는 클래스 공유라 테스트 간 파일 수 단언이 섞이지 않도록 비운다.
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ==================== 업로드 ====================

    @Test
    @DisplayName("jpg 800x600 업로드 → 성공·products/yyyy/MM/{ULID}.jpg·썸네일 400x300 별도 파일·서빙 200 image/jpeg + 1년 immutable 캐시")
    void uploadJpeg_createsThumbnailAndServes() throws Exception {
        JsonNode item = upload(file("photo.jpg", "image/jpeg", image(800, 600, "jpg"))).get("results").get(0);
        assertThat(item.get("success").asBoolean()).isTrue();
        assertThat(item.get("width").asInt()).isEqualTo(800);
        assertThat(item.get("height").asInt()).isEqualTo(600);
        String url = item.get("url").asText();
        String thumbnailUrl = item.get("thumbnailUrl").asText();
        assertThat(url).matches("/api/v1/files/products/\\d{4}/\\d{2}/[0-9A-Z]{26}\\.jpg");
        assertThat(thumbnailUrl).isEqualTo(url.replace(".jpg", "_thumb.jpg"));
        assertThat(Files.isRegularFile(uploadRoot.resolve(url.substring("/api/v1/files/".length())))).isTrue();

        byte[] thumbnail = mockMvc.perform(get(thumbnailUrl))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail));
        assertThat(decoded.getWidth()).isEqualTo(THUMBNAIL_WIDTH);
        assertThat(decoded.getHeight()).isEqualTo(300);

        mockMvc.perform(get(url)).andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/jpeg"));
    }

    @Test
    @DisplayName("png 300x200(썸네일 폭 이하) 업로드 → 확대 없이 thumbnailUrl=url·_thumb 파일 미생성·서빙 image/png")
    void uploadSmallPng_noUpscale() throws Exception {
        JsonNode item = upload(file("small.png", "image/png", image(300, 200, "png"))).get("results").get(0);
        assertThat(item.get("success").asBoolean()).isTrue();
        String url = item.get("url").asText();
        assertThat(url).endsWith(".png");
        assertThat(item.get("thumbnailUrl").asText()).isEqualTo(url);
        assertThat(Files.exists(uploadRoot.resolve(url.substring("/api/v1/files/".length()).replace(".png", "_thumb.png")))).isFalse();
        mockMvc.perform(get(url)).andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    @DisplayName("webp 업로드(TwelveMonkeys 읽기) → 성공·.webp 저장·서빙 image/webp")
    void uploadWebp_readsAndServes() throws Exception {
        JsonNode item = upload(file("tiny.webp", "image/webp", WEBP_1X1)).get("results").get(0);
        assertThat(item.get("success").asBoolean()).as(item.toString()).isTrue();
        assertThat(item.get("url").asText()).endsWith(".webp");
        assertThat(item.get("width").asInt()).isEqualTo(1);
        mockMvc.perform(get(item.get("url").asText())).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/webp"));
    }

    @Test
    @DisplayName("부분 실패: [정상 jpg·확장자 위조(텍스트를 .jpg/image/jpeg)·빈 파일·10MB 초과] → 200·성공 1·실패 3(코드별)")
    void upload_partialFailures() throws Exception {
        byte[] oversized = new byte[(int) MAX_FILE_SIZE + 1];
        oversized[0] = (byte) 0xFF;
        oversized[1] = (byte) 0xD8;
        oversized[2] = (byte) 0xFF;
        JsonNode response = upload(
                file("ok.jpg", "image/jpeg", image(500, 500, "jpg")),
                file("fake.jpg", "image/jpeg", "not an image".getBytes(StandardCharsets.UTF_8)),
                file("empty.jpg", "image/jpeg", new byte[0]),
                file("big.jpg", "image/jpeg", oversized));
        assertThat(response.get("successCount").asInt()).isEqualTo(1);
        assertThat(response.get("failureCount").asInt()).isEqualTo(3);
        assertThat(response.get("results").get(1).get("code").asText()).isEqualTo("UNSUPPORTED_FORMAT");
        assertThat(response.get("results").get(2).get("code").asText()).isEqualTo("EMPTY_FILE");
        assertThat(response.get("results").get(3).get("code").asText()).isEqualTo("FILE_TOO_LARGE");
        assertThat(response.get("results").get(1).hasNonNull("url")).as("실패 항목은 url 생략(NON_NULL)").isFalse();
    }

    @Test
    @DisplayName("장수 초과: 21장 → 400 MALFORMED_REQUEST·저장 0 / files 파트 누락 → 400")
    void upload_tooManyFiles() throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(UPLOAD_URL);
        byte[] png = image(10, 10, "png");
        for (int i = 0; i < 21; i++) {
            request.file(file("f" + i + ".png", "image/png", png));
        }
        mockMvc.perform(request.headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(countStoredFiles()).isZero();
        mockMvc.perform(multipart(UPLOAD_URL).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인가: BUYER 403·미인증 401 → 저장 0")
    void upload_forbiddenAndUnauthenticated() throws Exception {
        byte[] png = image(10, 10, "png");
        mockMvc.perform(multipart(UPLOAD_URL).file(file("a.png", "image/png", png)).headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart(UPLOAD_URL).file(file("a.png", "image/png", png)))
                .andExpect(status().isUnauthorized());
        assertThat(countStoredFiles()).isZero();
    }

    // ==================== 서빙 ====================

    @Test
    @DisplayName("서빙: 없는 파일 404 FILE_NOT_FOUND·traversal(..)은 404 또는 400·루트 밖 파일은 절대 서빙되지 않음")
    void serve_notFoundAndTraversal() throws Exception {
        Files.writeString(uploadRoot.resolveSibling("outside-secret.txt"), "secret");
        mockMvc.perform(get("/api/v1/files/products/2026/09/NOPE.jpg"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
        int traversalStatus = mockMvc.perform(get("/api/v1/files/products/../../outside-secret.txt"))
                .andReturn().getResponse().getStatus();
        assertThat(traversalStatus).isIn(400, 404);
        int encodedStatus = mockMvc.perform(get("/api/v1/files/%2e%2e/outside-secret.txt"))
                .andReturn().getResponse().getStatus();
        assertThat(encodedStatus).isIn(400, 404);
        mockMvc.perform(get("/api/v1/files/")).andExpect(status().is4xxClientError());
    }

    // ==================== 썸네일 동기화 ====================

    @Test
    @DisplayName("PUT images 대표 지정: 업로드 이미지 대표 → thumbnail_url=썸네일 URL / 외부 URL·클레임 경로·traversal 신규 → 400(D-174)·thumbnail 불변 / 대표 없음 → 유지")
    void replaceImages_syncsThumbnailUrl() throws Exception {
        seedProduct();
        JsonNode item = upload(file("main.jpg", "image/jpeg", image(1000, 500, "jpg"))).get("results").get(0);
        String url = item.get("url").asText();
        String thumbnailUrl = item.get("thumbnailUrl").asText();

        putImages("{\"images\":[{\"imageUrl\":\"" + url + "\",\"imageType\":\"GALLERY\",\"main\":true}]}");
        assertThat(currentThumbnailUrl()).isEqualTo(thumbnailUrl);

        for (String rejected : List.of("https://picsum.photos/seed/x/600/600", "/api/v1/files/claims/2026/09/x.jpg",
                "/api/v1/files/products/../claims/x.jpg", "/etc/passwd")) {
            putImagesExpecting("{\"images\":[{\"imageUrl\":\"" + rejected + "\",\"imageType\":\"GALLERY\",\"main\":true}]}",
                    status().isBadRequest());
            assertThat(currentThumbnailUrl()).as("거부된 URL은 thumbnail 무변경: " + rejected).isEqualTo(thumbnailUrl);
        }

        putImages("{\"images\":[{\"imageUrl\":\"" + url + "\",\"imageType\":\"DETAIL\",\"main\":false}]}");
        assertThat(currentThumbnailUrl()).as("대표 없음 → 유지").isEqualTo(thumbnailUrl);
    }

    // ==================== helpers ====================

    private JsonNode upload(MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(UPLOAD_URL);
        Arrays.stream(files).forEach(request::file);
        String body = mockMvc.perform(request.headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void putImages(String body) throws Exception {
        putImagesExpecting(body, status().isOk());
    }

    private void putImagesExpecting(String body, org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        mockMvc.perform(put("/api/v1/admin/products/" + PRODUCT_PID + "/images").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(expected);
    }

    private String currentThumbnailUrl() {
        return jdbc.queryForObject("SELECT thumbnail_url FROM product WHERE id = ?", String.class, PRODUCT_ID);
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("files", name, contentType, content);
    }

    private static byte[] image(int width, int height, String format) throws IOException {
        int type = "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.ORANGE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    private void clearUploadRoot() throws IOException {
        if (!Files.exists(uploadRoot)) {
            return;
        }
        try (var stream = Files.walk(uploadRoot)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .filter(path -> !path.equals(uploadRoot))
                    .forEach(path -> path.toFile().delete());
        }
    }

    private long countStoredFiles() throws IOException {
        if (!Files.exists(uploadRoot)) {
            return 0;
        }
        try (var stream = Files.walk(uploadRoot)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    private void seedProduct() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '트랙77카테고리', 0, 0, NOW(6), NOW(6))", CATEGORY_ID);
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, updated_at) "
                        + "VALUES (?, ?, '트랙77셀러', '대표', 'ACTIVE', 1000, NOW(6), NOW(6))",
                        SELLER_ID, "slr_" + ("T77SELLER" + "00000000000000000000000000").substring(0, 26));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, thumbnail_url, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, '트랙77상품', 'SALE', 10000, 'https://img/seed', NOW(6), NOW(6))",
                        PRODUCT_ID, PRODUCT_PID, SELLER_ID, CATEGORY_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM product_image WHERE product_id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'PRODUCT' AND target_id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM category WHERE id = ?", CATEGORY_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}
