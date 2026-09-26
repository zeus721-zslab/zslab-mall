package com.zslab.mall.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.attachment.repository.AttachmentRepository;
import com.zslab.mall.attachment.scheduler.AttachmentCleanupScheduler;
import com.zslab.mall.attachment.service.AttachmentCleanupService;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.file.service.ImageUploadService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 업로드 자원 고갈 방지·첨부 캐시·미연결 정리 통합 테스트(검수 4단계 D-174·실 MariaDB·임시 업로드 루트). 픽셀 폭탄(작은 파일·거대 해상도)
 * 즉시 거부, 구매자 첨부 한도 분리(5장·5MB·미연결 20), 클레임 첨부 서빙 캐시 금지·nosniff, 24시간 경과 미연결 첨부 정리 배치를 실 커밋 경로로
 * 검증한다. 정리 스케줄러 자동 발화는 끄고(킬스위치 빈 부재 검증 겸) 배치를 직접 생성해 호출한다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"zslab.attachment.cleanup.enabled=false"})
class UploadHardeningIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_UPLOAD_URL = "/api/v1/admin/files/images";
    private static final String CLAIM_UPLOAD_URL = "/api/v1/claims/attachments";
    private static final String SELLER_UPLOAD_URL = "/api/v1/seller/files/images";
    private static final long SELLER_USER_ID = 78103L;
    /** 구매자 첨부 요청 합계 상한(D-230·파일당 5MB × 5장 + 여유 1MB). 서비스 상수를 쓰지 않고 값으로 고정해 회귀를 잡는다. */
    private static final long CLAIM_REQUEST_LIMIT = 26L * 1024 * 1024;
    private static final long ADMIN_ID = 78100L;
    private static final long BUYER_ID = 78101L;
    private static final long OTHER_BUYER_ID = 78102L;
    private static final long SEED_ATTACHMENT_ID_BASE = 78100L;
    private static final int MAX_SIDE = 8_000;
    private static final long FIVE_MB = 5L * 1024 * 1024;
    private static final long PIXEL_BOMB_TIMEOUT_MS = 3_000L;
    private static final int EIGHT_BIT = 8;
    private static final int SIXTEEN_BIT = 16;
    private static final int COLOR_TYPE_RGB = 2;
    private static final int COLOR_TYPE_RGBA = 6;

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
    private ApplicationContext applicationContext;
    @Autowired
    private AttachmentRepository attachmentRepository;
    @Autowired
    private AttachmentCleanupService attachmentCleanupService;
    @Autowired
    private ImageUploadService imageUploadService;
    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() throws IOException {
        cleanup();
        clearUploadRoot();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ==================== STEP 276 픽셀 상한 ====================

    @Test
    @DisplayName("픽셀 폭탄: 헤더 20000x20000·8001x100·8000x5001·6000x6000·5000x5001(총 2,500만 초과·D-230) PNG(수백 바이트) → IMAGE_TOO_LARGE·저장 0·즉시 실패 / 8비트 RGBA 5000x5000(디코딩 1억 B ≤ 예산)은 통과 후 디코딩 실패 INVALID_IMAGE")
    void pixelBomb_rejectedBeforeDecoding() throws Exception {
        long started = System.currentTimeMillis();
        JsonNode results = upload(ADMIN_UPLOAD_URL, authHeaders.admin(ADMIN_ID),
                file("bomb.png", pngHeaderOnly(20_000, 20_000)),
                file("wide.png", pngHeaderOnly(MAX_SIDE + 1, 100)),
                file("pixels.png", pngHeaderOnly(MAX_SIDE, 5_001)),
                file("over-limit.png", pngHeaderOnly(6_000, 6_000)),
                file("boundary-over.png", pngHeaderOnly(5_000, 5_001)),
                file("truncated.png", pngHeaderWithEmptyIdat(5_000, 5_000, EIGHT_BIT, COLOR_TYPE_RGBA))).get("results");
        long elapsed = System.currentTimeMillis() - started;

        assertThat(results.get(0).get("code").asText()).isEqualTo("IMAGE_TOO_LARGE");
        assertThat(results.get(1).get("code").asText()).isEqualTo("IMAGE_TOO_LARGE");
        assertThat(results.get(2).get("code").asText()).isEqualTo("IMAGE_TOO_LARGE");
        // 헤더만 있어 디코딩하면 INVALID_IMAGE가 나올 파일 → IMAGE_TOO_LARGE면 디코딩 전 거부(D-230 총 픽셀 25,000,000)
        assertThat(results.get(3).get("code").asText()).isEqualTo("IMAGE_TOO_LARGE");
        assertThat(results.get(3).get("message").asText()).isEqualTo("이미지가 너무 큽니다(최대 약 5000×5000 픽셀, 8비트 색상).");
        assertThat(results.get(4).get("code").asText()).isEqualTo("IMAGE_TOO_LARGE");
        assertThat(results.get(5).get("code").asText()).as("한도 이내(정확히 25,000,000·8비트 RGBA 1억 B) 헤더·예산 통과 → 픽셀 데이터 없음 → 디코딩 실패")
                .isEqualTo("INVALID_IMAGE");
        assertThat(results.get(5).get("message").asText()).isEqualTo("이미지를 디코딩할 수 없습니다.");
        assertThat(countStoredFiles()).isZero();
        assertThat(elapsed).as("헤더만 읽고 거부 — 전체 디코딩 없음").isLessThan(PIXEL_BOMB_TIMEOUT_MS);
    }

    @Test
    @DisplayName("디코딩 바이트 예산(D-230): 총 픽셀 이내 16비트 RGBA 4000x4000(1.28억 B) · 16비트 RGB 4000x4000(0.96억 B + 썸네일 변환 복사 0.64억 B) → 디코딩 전 IMAGE_TOO_LARGE·저장 0 / 8비트 RGB 5000x5000(0.75억 B)은 예산 통과 후 디코딩 실패")
    void decodeBudget_rejectsHighBitDepthBeforeDecoding() throws Exception {
        JsonNode results = upload(ADMIN_UPLOAD_URL, authHeaders.admin(ADMIN_ID),
                file("deep-rgba.png", pngHeaderWithEmptyIdat(4_000, 4_000, SIXTEEN_BIT, COLOR_TYPE_RGBA)),
                file("deep-rgb.png", pngHeaderWithEmptyIdat(4_000, 4_000, SIXTEEN_BIT, COLOR_TYPE_RGB)),
                file("plain-rgb.png", pngHeaderWithEmptyIdat(5_000, 5_000, EIGHT_BIT, COLOR_TYPE_RGB))).get("results");

        assertThat(results.get(0).get("code").asText()).isEqualTo("IMAGE_TOO_LARGE");
        assertThat(results.get(0).get("message").asText()).isEqualTo("이미지가 너무 큽니다(최대 약 5000×5000 픽셀, 8비트 색상).");
        assertThat(results.get(1).get("code").asText()).isEqualTo("IMAGE_TOO_LARGE");
        assertThat(results.get(2).get("code").asText()).isEqualTo("INVALID_IMAGE");
        assertThat(results.get(2).get("message").asText()).isEqualTo("이미지를 디코딩할 수 없습니다.");
        assertThat(countStoredFiles()).isZero();
    }

    @Test
    @DisplayName("픽셀 상한은 구매자 첨부 경로에도 적용: 20000x20000 헤더 PNG → IMAGE_TOO_LARGE·attachment 행 0")
    void pixelBomb_rejectedOnClaimAttachmentPath() throws Exception {
        JsonNode results = upload(CLAIM_UPLOAD_URL, authHeaders.buyer(BUYER_ID), file("bomb.png", pngHeaderOnly(20_000, 20_000)))
                .get("results");
        assertThat(results.get(0).get("code").asText()).isEqualTo("IMAGE_TOO_LARGE");
        assertThat(unlinkedCount(BUYER_ID)).isZero();
    }

    // ==================== STEP 277 구매자 첨부 한도 ====================

    @Test
    @DisplayName("구매자 첨부 한도: 6장 → 400 / 5MB+1 → FILE_TOO_LARGE(관리자 같은 파일은 크기 통과·형식 실패) / 미연결 20개 보유 후 1장 → 400")
    void claimAttachmentLimits_separatedFromAdmin() throws Exception {
        MockMultipartHttpServletRequestBuilder sixFiles = multipart(CLAIM_UPLOAD_URL);
        for (int i = 0; i < 6; i++) {
            sixFiles.file(file("f" + i + ".png", png(10, 10)));
        }
        mockMvc.perform(sixFiles.headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isBadRequest());

        byte[] overFiveMb = new byte[(int) FIVE_MB + 1];
        Arrays.fill(overFiveMb, (byte) 'x');
        JsonNode buyerResult = upload(CLAIM_UPLOAD_URL, authHeaders.buyer(BUYER_ID), file("big.jpg", overFiveMb)).get("results").get(0);
        assertThat(buyerResult.get("code").asText()).isEqualTo("FILE_TOO_LARGE");
        JsonNode adminResult = upload(ADMIN_UPLOAD_URL, authHeaders.admin(ADMIN_ID), file("big.jpg", overFiveMb)).get("results").get(0);
        assertThat(adminResult.get("code").asText()).as("관리자 10MB 한도 유지 → 크기 통과·매직 바이트 실패").isEqualTo("UNSUPPORTED_FORMAT");

        for (int i = 0; i < 20; i++) {
            seedAttachment(SEED_ATTACHMENT_ID_BASE + i, BUYER_ID, null, "/api/v1/files/claims/2026/09/seed" + i + ".png",
                    LocalDateTime.now());
        }
        mockMvc.perform(multipart(CLAIM_UPLOAD_URL).file(file("one-more.png", png(10, 10))).headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(unlinkedCount(BUYER_ID)).isEqualTo(20);
        // 타 사용자는 영향 없음
        assertThat(upload(CLAIM_UPLOAD_URL, authHeaders.buyer(OTHER_BUYER_ID), file("ok.png", png(10, 10)))
                .get("successCount").asInt()).isEqualTo(1);
    }

    // ==================== D-230 구매자 첨부 요청 합계 상한(멀티파트 파싱 전 필터) ====================
    // MockMvc는 서블릿 멀티파트 파서를 거치지 않아 필터의 Content-Length 판정만 검증한다(MockHttpServletRequest 길이 = content 바이트 수).

    @Test
    @DisplayName("구매자 첨부 합계 상한: 26MB+1 본문 → 413 PAYLOAD_TOO_LARGE(파싱 전 거부)·첨부 행 0 / 정확히 26MB → 필터 통과(파트 없음 400)")
    void claimAttachmentRequestTotal_overLimit_returns413() throws Exception {
        mockMvc.perform(post(CLAIM_UPLOAD_URL).headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.MULTIPART_FORM_DATA).content(new byte[(int) CLAIM_REQUEST_LIMIT + 1]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(unlinkedCount(BUYER_ID)).isZero();

        mockMvc.perform(post(CLAIM_UPLOAD_URL).headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.MULTIPART_FORM_DATA).content(new byte[(int) CLAIM_REQUEST_LIMIT]))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("구매자 첨부 합계 상한은 인코딩 경로 변형(attachment%73 → 디코딩하면 같은 핸들러)에도 적용 → 413")
    void claimAttachmentRequestTotal_encodedPathVariant_returns413() throws Exception {
        mockMvc.perform(post(URI.create("/api/v1/claims/attachment%73")).headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.MULTIPART_FORM_DATA).content(new byte[(int) CLAIM_REQUEST_LIMIT + 1]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    @DisplayName("구매자 첨부 청크 전송(Content-Length 없음 + Transfer-Encoding) → 411 LENGTH_REQUIRED")
    void claimAttachmentChunked_returns411() throws Exception {
        mockMvc.perform(post(CLAIM_UPLOAD_URL).headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.MULTIPART_FORM_DATA).header(HttpHeaders.TRANSFER_ENCODING, "chunked"))
                .andExpect(status().isLengthRequired())
                .andExpect(jsonPath("$.code").value("LENGTH_REQUIRED"));
    }

    @Test
    @DisplayName("관리자·셀러 업로드는 구매자 합계 상한 불변: 26MB+1 본문 → 413·411 아님(필터 미적용)")
    void adminAndSellerUpload_notAffectedByClaimRequestLimit() throws Exception {
        byte[] overClaimLimit = new byte[(int) CLAIM_REQUEST_LIMIT + 1];
        int adminStatus = mockMvc.perform(post(ADMIN_UPLOAD_URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.MULTIPART_FORM_DATA).content(overClaimLimit))
                .andReturn().getResponse().getStatus();
        int sellerStatus = mockMvc.perform(post(SELLER_UPLOAD_URL).headers(authHeaders.seller(SELLER_USER_ID))
                        .contentType(MediaType.MULTIPART_FORM_DATA).content(overClaimLimit))
                .andReturn().getResponse().getStatus();
        assertThat(adminStatus).isNotIn(413, 411);
        assertThat(sellerStatus).isNotIn(413, 411);
    }

    // ==================== STEP 278 캐시 헤더 ====================

    @Test
    @DisplayName("서빙 캐시: 클레임 첨부 URL → 업로더 200·익명 404(D-176) 모두 Cache-Control no-store+private / 상품 이미지 → 익명 200 public immutable 유지 / 둘 다 X-Content-Type-Options nosniff")
    void servingCacheHeaders_byPathPrefix() throws Exception {
        String claimUrl = upload(CLAIM_UPLOAD_URL, authHeaders.buyer(BUYER_ID), file("photo.png", png(10, 10)))
                .get("results").get(0).get("url").asText();
        assertThat(claimUrl).startsWith("/api/v1/files/claims/");
        String productUrl = upload(ADMIN_UPLOAD_URL, authHeaders.admin(ADMIN_ID), file("product.png", png(10, 10)))
                .get("results").get(0).get("url").asText();
        assertThat(productUrl).startsWith("/api/v1/files/products/");

        String claimCache = mockMvc.perform(get(claimUrl).headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getHeader("Cache-Control");
        assertThat(claimCache).contains("no-store").contains("private").doesNotContain("public");
        // D-176: 실제 업로드 흐름으로 만든 미연결 첨부도 익명이면 404(존재 비노출)·캐시 금지 유지
        String anonymousClaimCache = mockMvc.perform(get(claimUrl))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getHeader("Cache-Control");
        assertThat(anonymousClaimCache).contains("no-store").contains("private").doesNotContain("public");

        mockMvc.perform(get(productUrl))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
    }

    // ==================== STEP 279 미연결 첨부 정리 ====================

    @Test
    @DisplayName("정리 배치: 25시간 경과 미연결 → 행·원본·썸네일 삭제 / 연결된 25시간·미연결 1시간 → 보존 / 삭제 직전 연결(조건부 DELETE 0) → 보존 / 킬스위치 빈 부재")
    void cleanupBatch_deletesOnlyStaleUnlinked() throws Exception {
        assertThat(applicationContext.getBeanNamesForType(AttachmentCleanupScheduler.class)).as("킬스위치 false → 빈 없음").isEmpty();

        LocalDateTime stale = LocalDateTime.now().minusHours(25);
        long staleUnlinkedId = SEED_ATTACHMENT_ID_BASE + 50;
        long staleLinkedId = SEED_ATTACHMENT_ID_BASE + 51;
        long freshUnlinkedId = SEED_ATTACHMENT_ID_BASE + 52;
        long raceId = SEED_ATTACHMENT_ID_BASE + 53;
        String staleKey = "claims/2026/09/stale.png";
        String staleThumbKey = "claims/2026/09/stale_thumb.png";
        String linkedKey = "claims/2026/09/linked.png";
        String freshKey = "claims/2026/09/fresh.png";
        writeStoredFile(staleKey);
        writeStoredFile(staleThumbKey);
        writeStoredFile(linkedKey);
        writeStoredFile(freshKey);
        seedAttachment(staleUnlinkedId, BUYER_ID, null, "/api/v1/files/" + staleKey, stale);
        seedAttachment(staleLinkedId, BUYER_ID, 999_001L, "/api/v1/files/" + linkedKey, stale);
        seedAttachment(freshUnlinkedId, BUYER_ID, null, "/api/v1/files/" + freshKey, LocalDateTime.now().minusHours(1));
        seedAttachment(raceId, BUYER_ID, null, "/api/v1/files/claims/2026/09/race.png", stale);

        // 삭제 직전 연결 경합: 조회~조건부 DELETE 사이에 연결된 첨부는 영향 행 0으로 보존된다(리포지토리 조건부 DELETE 직접 검증).
        jdbc.update("UPDATE attachment SET target_id = ? WHERE id = ?", 999_002L, raceId);
        Integer raceAffected = new TransactionTemplate(txManager).execute(status -> attachmentRepository.deleteIfUnlinked(raceId));
        assertThat(raceAffected).isZero();

        AttachmentCleanupScheduler scheduler =
                new AttachmentCleanupScheduler(attachmentRepository, attachmentCleanupService, imageUploadService);
        scheduler.cleanupBatch();

        assertThat(exists(staleUnlinkedId)).as("25h 미연결 → 행 삭제").isFalse();
        assertThat(Files.exists(uploadRoot.resolve(staleKey))).as("원본 파일 삭제").isFalse();
        assertThat(Files.exists(uploadRoot.resolve(staleThumbKey))).as("썸네일 삭제").isFalse();
        assertThat(exists(staleLinkedId)).as("연결된 첨부 보존").isTrue();
        assertThat(Files.exists(uploadRoot.resolve(linkedKey))).isTrue();
        assertThat(exists(freshUnlinkedId)).as("24h 미만 보존").isTrue();
        assertThat(Files.exists(uploadRoot.resolve(freshKey))).isTrue();
        assertThat(exists(raceId)).as("삭제 직전 연결 → 보존").isTrue();

        // 재실행 멱등: 대상 없음·변화 없음
        scheduler.cleanupBatch();
        assertThat(exists(staleLinkedId)).isTrue();
        assertThat(exists(freshUnlinkedId)).isTrue();
    }

    // ==================== helpers (모든 SQL은 ? 바인딩·문자열 concat 없음) ====================

    private JsonNode upload(String url, org.springframework.http.HttpHeaders headers, MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(url);
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        String body = mockMvc.perform(request.headers(headers))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("files", name, name.endsWith(".jpg") ? "image/jpeg" : "image/png", content);
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    /** PNG 시그니처 + IHDR(폭·높이·8bit RGB) + IEND만 담은 수십 바이트 픽스처 — 헤더는 정상, 픽셀 데이터 없음(픽셀 폭탄 모사). */
    private static byte[] pngHeaderOnly(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = 8;   // bit depth
        ihdr[9] = 2;   // color type RGB
        writeChunk(output, "IHDR", ihdr);
        writeChunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }

    /**
     * PNG 시그니처 + IHDR(폭·높이·비트 깊이·색 형식) + 빈 IDAT + IEND. reader가 IDAT 앞까지 메타데이터를 읽어 밴드·샘플 비트(raw type)를
     * 헤더 단계에서 확정할 수 있고, 픽셀 데이터가 없어 실제 디코딩은 실패한다(예산 통과 여부와 디코딩 도달 여부를 구분하는 픽스처·D-230).
     */
    static byte[] pngHeaderWithEmptyIdat(int width, int height, int bitDepth, int colorType) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = (byte) bitDepth;
        ihdr[9] = (byte) colorType;
        writeChunk(output, "IHDR", ihdr);
        writeChunk(output, "IDAT", new byte[0]);
        writeChunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream output, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        byte[] length = new byte[4];
        putInt(length, 0, data.length);
        output.write(length);
        output.write(typeBytes);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        byte[] crcBytes = new byte[4];
        putInt(crcBytes, 0, (int) crc.getValue());
        output.write(crcBytes);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private void seedAttachment(long id, long uploadedBy, Long targetId, String filePath, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO attachment (id, public_id, target_type, target_id, file_name, file_path, mime_type, file_size, "
                        + "display_order, uploaded_by, created_at, updated_at) "
                        + "VALUES (?, ?, 'CLAIM', ?, 'seed.png', ?, 'image/png', 1, 0, ?, ?, ?)",
                id, "att_" + ("T4ATT" + id + "00000000000000000000000000").substring(0, 26), targetId, filePath, uploadedBy,
                createdAt, createdAt);
    }

    private void writeStoredFile(String relativeKey) throws IOException {
        Path target = uploadRoot.resolve(relativeKey);
        Files.createDirectories(target.getParent());
        Files.write(target, png(10, 10));
    }

    private boolean exists(long attachmentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM attachment WHERE id = ?", Integer.class, attachmentId) > 0;
    }

    private long unlinkedCount(long uploadedBy) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM attachment WHERE uploaded_by = ? AND target_id IS NULL", Long.class, uploadedBy);
    }

    private long countStoredFiles() throws IOException {
        if (!Files.exists(uploadRoot)) {
            return 0;
        }
        try (var stream = Files.walk(uploadRoot)) {
            return stream.filter(Files::isRegularFile).count();
        }
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

    private void cleanup() {
        jdbc.update("DELETE FROM attachment WHERE uploaded_by IN (?, ?)", BUYER_ID, OTHER_BUYER_ID);
    }
}
