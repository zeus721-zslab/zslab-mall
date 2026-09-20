package com.zslab.mall.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 셀러 이미지 업로드 통합 테스트(Track 90-C-1·실 MariaDB·임시 업로드 루트). 관리자 업로드와 동일 서비스를 쓰므로 형식·썸네일 검증은
 * {@link FileUploadServingIntegrationTest}에 맡기고, 여기서는 셀러 경로의 인가·D-190 상태 가드(POST=쓰기)·저장 경로(products/)·
 * 파일별 결과 계약만 확인한다. {@code upload.path}를 @TempDir로 덮어써 실제 업로드 경로를 오염시키지 않는다.
 */
@AutoConfigureMockMvc
class SellerFileUploadIntegrationTest extends AbstractIntegrationTest {

    private static final String UPLOAD_URL = "/api/v1/seller/files/images";
    private static final long USER_A = 9730L;
    private static final long SELLER_A = 9730L;
    private static final long BUYER_ID = 9731L;

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
        seedSeller(SellerStatus.ACTIVE);
        clearUploadRoot(); // static @TempDir는 클래스 공유라 테스트 간 파일 수 단언이 섞이지 않도록 비운다.
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 ACTIVE 셀러 png 800x600 업로드 → 200·success·products/yyyy/MM/{ULID}.png·썸네일 URL·파일 2개 저장·공개 서빙 200")
    void upload_activeSeller_storesUnderProducts() throws Exception {
        JsonNode response = upload(authHeadersOfSeller(), file("photo.png", "image/png", image(800, 600, "png")));
        assertThat(response.get("successCount").asInt()).isEqualTo(1);
        assertThat(response.get("failureCount").asInt()).isZero();
        JsonNode item = response.get("results").get(0);
        assertThat(item.get("success").asBoolean()).isTrue();
        assertThat(item.get("fileName").asText()).isEqualTo("photo.png");
        String url = item.get("url").asText();
        assertThat(url).matches("/api/v1/files/products/\\d{4}/\\d{2}/[0-9A-Z]{26}\\.png");
        assertThat(item.get("thumbnailUrl").asText()).isEqualTo(url.replace(".png", "_thumb.png"));
        assertThat(countStoredFiles()).isEqualTo(2);

        // 발급 URL은 셀러 이미지 등록이 요구하는 서버 발급 상품 경로(D-174 PRODUCT_URL_PREFIX)이며 공개 서빙된다.
        mockMvc.perform(get(url)).andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    @DisplayName("T2 부분 실패: [정상 png·확장자 위조 텍스트·빈 파일] → 200·성공 1·실패 2(UNSUPPORTED_FORMAT·EMPTY_FILE)·저장은 성공분만")
    void upload_partialFailure() throws Exception {
        JsonNode response = upload(authHeadersOfSeller(),
                file("ok.png", "image/png", image(10, 10, "png")),
                file("fake.jpg", "image/jpeg", "not an image".getBytes(StandardCharsets.UTF_8)),
                file("empty.png", "image/png", new byte[0]));
        assertThat(response.get("successCount").asInt()).isEqualTo(1);
        assertThat(response.get("failureCount").asInt()).isEqualTo(2);
        assertThat(response.get("results").get(1).get("code").asText()).isEqualTo("UNSUPPORTED_FORMAT");
        assertThat(response.get("results").get(2).get("code").asText()).isEqualTo("EMPTY_FILE");
        // 10x10은 썸네일 폭 이하라 원본 1파일만 저장된다.
        assertThat(countStoredFiles()).isEqualTo(1);
    }

    @Test
    @DisplayName("T3 files 파트 누락 → 400·저장 0")
    void upload_missingFilesPart_returns400() throws Exception {
        mockMvc.perform(multipart(UPLOAD_URL).headers(authHeadersOfSeller()))
                .andExpect(status().isBadRequest());
        assertThat(countStoredFiles()).isZero();
    }

    @Test
    @DisplayName("T4 D-190: SUSPENDED 셀러 업로드(POST=쓰기) → 403 SELLER_SUSPENDED·저장 0")
    void upload_suspendedSeller_returns403() throws Exception {
        cleanup();
        seedSeller(SellerStatus.SUSPENDED);

        mockMvc.perform(multipart(UPLOAD_URL).file(file("a.png", "image/png", image(10, 10, "png"))).headers(authHeadersOfSeller()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_SUSPENDED"));
        assertThat(countStoredFiles()).isZero();
    }

    @ParameterizedTest(name = "{0} 셀러 업로드 → 401")
    @EnumSource(value = SellerStatus.class, names = {"PENDING", "TERMINATED"})
    @DisplayName("T5 D-190: 세션 불가 상태 → 401 UNAUTHENTICATED·저장 0")
    void upload_sessionDenied_returns401(SellerStatus status) throws Exception {
        cleanup();
        seedSeller(status);

        mockMvc.perform(multipart(UPLOAD_URL).file(file("a.png", "image/png", image(10, 10, "png"))).headers(authHeadersOfSeller()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(countStoredFiles()).isZero();
    }

    @Test
    @DisplayName("T6 인가: BUYER 403·미인증 401 → 저장 0")
    void upload_forbiddenAndUnauthenticated() throws Exception {
        byte[] png = image(10, 10, "png");
        mockMvc.perform(multipart(UPLOAD_URL).file(file("a.png", "image/png", png)).headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart(UPLOAD_URL).file(file("a.png", "image/png", png)))
                .andExpect(status().isUnauthorized());
        assertThat(countStoredFiles()).isZero();
    }

    // ==================== helpers ====================

    private HttpHeaders authHeadersOfSeller() {
        return authHeaders.seller(USER_A);
    }

    private JsonNode upload(HttpHeaders headers, MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(UPLOAD_URL);
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        String body = mockMvc.perform(request.headers(headers))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
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
            stream.sorted(Comparator.reverseOrder())
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

    private void seedSeller(SellerStatus status) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        USER_A, pid("usr_", "SFUUSA"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '업로드셀러A', '대표', ?, NOW(6), NOW(6))", SELLER_A, pid("slr_", "SFUSLA"), status.name());
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                        + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", USER_A, SELLER_A);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM seller_user WHERE user_id = ?", USER_A);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_A);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_A);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
