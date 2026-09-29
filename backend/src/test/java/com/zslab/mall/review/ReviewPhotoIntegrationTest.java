package com.zslab.mall.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.attachment.repository.AttachmentRepository;
import com.zslab.mall.attachment.scheduler.AttachmentCleanupScheduler;
import com.zslab.mall.attachment.service.AttachmentCleanupService;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.file.service.ImageUploadService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 리뷰 사진 업로드·연결 통합 테스트(Track 106-1 STEP 2·실 MariaDB·실 파일 저장). 재인코딩(EXIF 제거·Orientation 반영·투명 PNG 보존)·한도·
 * 요청 크기 필터·소유/유형/재사용 차단·정리 배치 REVIEW 확장을 검증한다. 클레임 쪽 교차 유형 거부는
 * {@code ClaimReturnIntegrationTest#attachments_reviewAttachmentRejected}(RED 선증명)에 있다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"zslab.attachment.cleanup.enabled=false"})
class ReviewPhotoIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10620L;
    private static final long BAND_TO = 10639L;
    private static final long BUYER = 10621L;
    private static final long OTHER_BUYER = 10622L;
    private static final long SELLER = 10623L;
    private static final long PRODUCT = 10624L;
    private static final long VARIANT = 10625L;
    private static final long CATEGORY = 10626L;
    private static final long ORDER = 10627L;
    private static final long ITEM = 10628L;
    private static final long SECOND_ORDER = 10629L;
    private static final long SECOND_ITEM = 10630L;
    private static final long SEED_ATTACHMENT_BASE = 10631L;
    /** 리뷰 사진 요청 상한(1장 × 5MB + 여유 1MB). 서비스 상수를 쓰지 않고 값으로 고정해 회귀를 잡는다. */
    private static final long PHOTO_REQUEST_LIMIT = 6L * 1024 * 1024;
    private static final long FIVE_MB = 5L * 1024 * 1024;
    private static final int UNLINKED_CAP = 20;
    private static final String UPLOAD_URL = "/api/v1/reviews/attachments";
    private static final String CREATE_URL = "/api/v1/reviews";
    private static final int EXIF_ORIENTATION_ROTATE_CW = 6;
    /** 썸네일(가로 400 초과일 때 생성)까지 검사하려고 가로를 썸네일 폭보다 크게 둔다. */
    private static final int METADATA_FIXTURE_WIDTH = 600;
    private static final int METADATA_FIXTURE_HEIGHT = 400;
    private static final int JPEG_SOI = 0xD8;
    private static final int JPEG_EOI = 0xD9;
    private static final int JPEG_SOS = 0xDA;
    private static final int JPEG_APP0 = 0xE0;
    private static final int JPEG_APP1 = 0xE1;
    private static final int JPEG_APP13 = 0xED;
    private static final int JPEG_APP15 = 0xEF;
    private static final int PNG_SIGNATURE_LENGTH = 8;
    /** 청크 길이 4 + 유형 4 + CRC 4. */
    private static final int PNG_CHUNK_OVERHEAD = 12;
    private static final int PNG_IHDR_LENGTH = 13;
    private static final byte[] EXIF_IDENTIFIER = {'E', 'x', 'i', 'f', 0, 0};
    private static final byte[] XMP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] IPTC_IDENTIFIER = "Photoshop 3.0\0".getBytes(StandardCharsets.US_ASCII);

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
    @Autowired
    private AttachmentRepository attachmentRepository;
    @Autowired
    private AttachmentCleanupService attachmentCleanupService;
    @Autowired
    private ImageUploadService imageUploadService;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private ReviewFixture fixture;
    private String itemPid;
    private String secondItemPid;

    @BeforeEach
    void setUp() {
        fixture = new ReviewFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(BUYER);
        fixture.seedUser(OTHER_BUYER);
        fixture.seedCatalog(SELLER, PRODUCT, VARIANT, CATEGORY);
        itemPid = fixture.seedItem(ORDER, ITEM, BUYER, PRODUCT, VARIANT, SELLER, "CONFIRMED", null);
        secondItemPid = fixture.seedItem(SECOND_ORDER, SECOND_ITEM, BUYER, PRODUCT, VARIANT, SELLER, "CONFIRMED", null);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    // ==================== 재인코딩 ====================

    @Test
    @DisplayName("P1 EXIF(Orientation 6·GPS) JPEG → 200·미연결 REVIEW 행·저장본에 Exif 세그먼트 없음·시계 방향 90도 회전 반영(가로·세로 교체·왼쪽 빨강 → 위쪽)")
    void upload_jpegWithExif_storesRotatedWithoutExif() throws Exception {
        byte[] source = withExif(leftRedRightBlueJpeg(40, 20), EXIF_ORIENTATION_ROTATE_CW);
        assertThat(indexOf(source, "Exif\0".getBytes(StandardCharsets.US_ASCII))).as("픽스처에 Exif가 있어야 검증 의미가 있다")
                .isNotNegative();

        JsonNode item = uploadOk(BUYER, new MockMultipartFile("files", "phone.jpg", "image/jpeg", source));

        String url = item.get("url").asText();
        assertThat(url).startsWith("/api/v1/files/reviews/");
        assertThat(jdbc.queryForMap("SELECT target_type, target_id, uploaded_by FROM attachment WHERE public_id = ?",
                item.get("attachmentId").asText()))
                .containsEntry("target_type", "REVIEW")
                .containsEntry("target_id", null)
                .containsEntry("uploaded_by", BUYER);
        byte[] stored = storedBytes(url);
        assertThat(indexOf(stored, "Exif\0".getBytes(StandardCharsets.US_ASCII))).as("저장본 EXIF 제거").isNegative();
        BufferedImage storedImage = ImageIO.read(new ByteArrayInputStream(stored));
        assertThat(storedImage.getWidth()).isEqualTo(20);
        assertThat(storedImage.getHeight()).isEqualTo(40);
        assertThat(new Color(storedImage.getRGB(10, 5)).getRed()).as("위쪽 = 원본 왼쪽(빨강)").isGreaterThan(200);
        assertThat(new Color(storedImage.getRGB(10, 35)).getBlue()).as("아래쪽 = 원본 오른쪽(파랑)").isGreaterThan(200);
    }

    @Test
    @DisplayName("P2 투명 PNG + tEXt 청크 → 200·저장본 알파 유지·tEXt 청크 없음")
    void upload_transparentPngWithText_keepsAlphaDropsText() throws Exception {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(5, 5, Color.RED.getRGB());
        byte[] source = withTextChunk(encode(image, "png"), "GPS\0secret-location");

        JsonNode item = uploadOk(BUYER, new MockMultipartFile("files", "clear.png", "image/png", source));

        byte[] stored = storedBytes(item.get("url").asText());
        assertThat(indexOf(stored, "tEXt".getBytes(StandardCharsets.US_ASCII))).isNegative();
        BufferedImage storedImage = ImageIO.read(new ByteArrayInputStream(stored));
        assertThat(storedImage.getRGB(0, 0) >>> 24).as("투명 픽셀 알파 0 유지").isZero();
        assertThat(storedImage.getRGB(5, 5)).isEqualTo(Color.RED.getRGB());
    }

    @Test
    @DisplayName("P14 메타 블록 제거(JPEG): APP1 Exif(GPS·Make/Model)·APP1 XMP·APP13 IPTC를 끼운 입력 → 저장본·썸네일 세그먼트 구조 파싱 결과 APP0(JFIF) 외 APPn 0개")
    void upload_jpegMetadataBlocks_removedFromStoredAndThumbnail() throws Exception {
        byte[] source = withJpegSegments(leftRedRightBlueJpeg(METADATA_FIXTURE_WIDTH, METADATA_FIXTURE_HEIGHT), List.of(
                jpegSegment(JPEG_APP1, concat(EXIF_IDENTIFIER, deviceAndGpsTiff())),
                jpegSegment(JPEG_APP1, concat(XMP_IDENTIFIER, "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF/></x:xmpmeta>"
                        .getBytes(StandardCharsets.UTF_8))),
                jpegSegment(JPEG_APP13, concat(IPTC_IDENTIFIER, iptcResource()))));
        assertThat(jpegAppSegments(source)).as("입력에 세 블록이 있어야 검증 의미가 있다")
                .contains("APP1:Exif", "APP1:http://ns.adobe.com/xap/1.0/", "APP13:Photoshop 3.0");

        JsonNode item = uploadOk(BUYER, new MockMultipartFile("files", "meta.jpg", "image/jpeg", source));

        assertThat(item.get("thumbnailUrl").asText()).as("썸네일도 검사하려면 생성돼야 한다").isNotEqualTo(item.get("url").asText());
        assertThat(jpegAppSegments(storedBytes(item.get("url").asText()))).as("저장본").containsOnly("APP0:JFIF");
        assertThat(jpegAppSegments(storedBytes(item.get("thumbnailUrl").asText()))).as("썸네일").containsOnly("APP0:JFIF");
    }

    @Test
    @DisplayName("P15 메타 청크 제거(PNG): eXIf·tEXt·zTXt·iTXt를 끼운 입력 → 저장본·썸네일 청크 구조 파싱 결과 네 청크 0개")
    void upload_pngMetadataChunks_removedFromStoredAndThumbnail() throws Exception {
        byte[] png = encode(new BufferedImage(METADATA_FIXTURE_WIDTH, METADATA_FIXTURE_HEIGHT, BufferedImage.TYPE_INT_RGB), "png");
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        writeChunk(chunks, "eXIf", deviceAndGpsTiff());
        writeChunk(chunks, "tEXt", "Author\0zslab".getBytes(StandardCharsets.ISO_8859_1));
        writeChunk(chunks, "zTXt", concat("Comment\0\0".getBytes(StandardCharsets.ISO_8859_1), deflate("GPS 37.5665,126.9780")));
        writeChunk(chunks, "iTXt", "Location\0\0\0\0\0Seoul".getBytes(StandardCharsets.UTF_8));
        byte[] source = insertAfterHeader(png, chunks.toByteArray());
        assertThat(pngChunkTypes(source)).as("입력에 네 청크가 있어야 검증 의미가 있다").contains("eXIf", "tEXt", "zTXt", "iTXt");

        JsonNode item = uploadOk(BUYER, new MockMultipartFile("files", "meta.png", "image/png", source));

        assertThat(item.get("thumbnailUrl").asText()).isNotEqualTo(item.get("url").asText());
        for (String url : List.of(item.get("url").asText(), item.get("thumbnailUrl").asText())) {
            assertThat(pngChunkTypes(storedBytes(url))).as(url).doesNotContain("eXIf", "tEXt", "zTXt", "iTXt").contains("IHDR", "IEND");
        }
    }

    @Test
    @DisplayName("P9 재인코딩 후 크기 상한: 5MB 이하 팔레트 PNG(무작위 256색)가 트루컬러 재기록으로 5MB를 넘으면 → FILE_TOO_LARGE·파일·행 없음")
    void upload_reencodedOverFileLimit_rejected() throws Exception {
        byte[] source = randomPalettePng(2_000, 2_000);
        assertThat((long) source.length).as("입력은 파일당 한도 이하여야 검증 의미가 있다").isLessThanOrEqualTo(FIVE_MB);
        long storedBefore = storedFileCount();

        JsonNode item = upload(BUYER, new MockMultipartFile("files", "palette.png", "image/png", source));

        assertThat(item.get("success").asBoolean()).isFalse();
        assertThat(item.get("code").asText()).isEqualTo("FILE_TOO_LARGE");
        assertThat(reviewAttachmentCount(BUYER)).isZero();
        assertThat(storedFileCount()).as("거부된 파일은 저장하지 않는다").isEqualTo(storedBefore);
    }

    /** 업로드 루트의 파일 수(클래스 공용 @TempDir라 다른 테스트 파일이 남아 있을 수 있어 전후 차이로 본다). */
    private static long storedFileCount() throws IOException {
        try (java.util.stream.Stream<Path> stored = Files.walk(uploadRoot)) {
            return stored.filter(Files::isRegularFile).count();
        }
    }

    @Test
    @DisplayName("P10 24MP(6000×4000) 세로 촬영 JPEG(Orientation 6·GPS) → 200 · 저장본 긴 변 2048(1365×2048)·회전 유지(위쪽 = 원본 왼쪽 빨강)·EXIF 없음·썸네일 생성")
    void upload_highResolutionPortrait_downscaledAndRotated() throws Exception {
        byte[] source = withExif(leftRedRightBlueJpeg(6_000, 4_000), EXIF_ORIENTATION_ROTATE_CW);
        assertThat((long) source.length).isLessThanOrEqualTo(FIVE_MB);

        JsonNode item = uploadOk(BUYER, new MockMultipartFile("files", "24mp.jpg", "image/jpeg", source));

        assertThat(item.get("url").asText()).isNotEqualTo(item.get("thumbnailUrl").asText());
        byte[] stored = storedBytes(item.get("url").asText());
        assertThat(indexOf(stored, "Exif\0".getBytes(StandardCharsets.US_ASCII))).isNegative();
        BufferedImage storedImage = ImageIO.read(new ByteArrayInputStream(stored));
        assertThat(storedImage.getHeight()).isEqualTo(2_048);
        assertThat(storedImage.getWidth()).isEqualTo(1_365);
        assertThat(new Color(storedImage.getRGB(680, 100)).getRed()).as("위쪽 = 원본 왼쪽(빨강)").isGreaterThan(200);
        assertThat(new Color(storedImage.getRGB(680, 1_950)).getBlue()).as("아래쪽 = 원본 오른쪽(파랑)").isGreaterThan(200);
    }

    @Test
    @DisplayName("P11 5천만 화소 경계: 8000×6251(50,008,000) → IMAGE_TOO_LARGE·실제 한도 문구 / 7071×7071(49,999,041) → 한도 통과 후 픽셀 없음 INVALID_IMAGE")
    void upload_overFiftyMegapixels_rejectedWithMatchingMessage() throws Exception {
        JsonNode over = upload(BUYER, new MockMultipartFile("files", "over.png", "image/png", pngHeaderOnly(8_000, 6_251)));
        assertThat(over.get("code").asText()).isEqualTo("IMAGE_TOO_LARGE");
        assertThat(over.get("message").asText()).isEqualTo("사진 해상도가 너무 큽니다(최대 약 5천만 화소·한 변 2만 픽셀).");

        JsonNode under = upload(BUYER, new MockMultipartFile("files", "under.png", "image/png", pngHeaderOnly(7_071, 7_071)));
        assertThat(under.get("code").asText()).as("화소 한도·디코딩 예산은 통과하고 픽셀 데이터가 없어 디코딩에서 실패").isEqualTo("INVALID_IMAGE");
        assertThat(under.get("message").asText()).as("헤더 판독 실패(같은 코드)가 아니라 디코딩 단계 실패").isEqualTo("이미지를 디코딩할 수 없습니다.");
        assertThat(reviewAttachmentCount(BUYER)).isZero();
    }

    @Test
    @DisplayName("P12 극단 종횡비: 한 변 20,001px(20,001×10·화소는 한도 안) → 디코딩 전 IMAGE_TOO_LARGE(원본 폭 행 버퍼 폭탄 차단)")
    void upload_extremeAspectRatio_rejectedBeforeDecoding() throws Exception {
        JsonNode wide = upload(BUYER, new MockMultipartFile("files", "wide.png", "image/png", pngHeaderOnly(20_001, 10)));

        assertThat(wide.get("code").asText()).isEqualTo("IMAGE_TOO_LARGE");
        assertThat(wide.get("message").asText()).isEqualTo("사진 해상도가 너무 큽니다(최대 약 5천만 화소·한 변 2만 픽셀).");
    }

    @Test
    @DisplayName("P13 서브샘플링 없는 구간(긴 변 2048~4095): 4000×4000(16MP) JPEG → 200·저장본 2048×2048(원본 크기 회전 사본 없이 축소)")
    void upload_sixteenMegapixelWithoutSubsampling_accepted() throws Exception {
        byte[] source = leftRedRightBlueJpeg(4_000, 4_000);

        JsonNode item = uploadOk(BUYER, new MockMultipartFile("files", "16mp.jpg", "image/jpeg", source));

        BufferedImage storedImage = ImageIO.read(new ByteArrayInputStream(storedBytes(item.get("url").asText())));
        assertThat(storedImage.getWidth()).isEqualTo(2_048);
        assertThat(storedImage.getHeight()).isEqualTo(2_048);
    }

    // ==================== 한도 ====================

    @Test
    @DisplayName("P3 형식·크기·장수: gif 매직 → UNSUPPORTED_FORMAT / 5MB+1 → FILE_TOO_LARGE / 한 요청 2장 → 400 · 행 0")
    void upload_formatSizeCount_rejected() throws Exception {
        JsonNode gif = upload(BUYER, new MockMultipartFile("files", "a.gif", "image/gif", "GIF89a....".getBytes(StandardCharsets.US_ASCII)));
        assertThat(gif.get("code").asText()).isEqualTo("UNSUPPORTED_FORMAT");
        JsonNode large = upload(BUYER, new MockMultipartFile("files", "big.jpg", "image/jpeg", new byte[(int) FIVE_MB + 1]));
        assertThat(large.get("code").asText()).isEqualTo("FILE_TOO_LARGE");

        mockMvc.perform(multipart(UPLOAD_URL)
                        .file(new MockMultipartFile("files", "a.png", "image/png", encode(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png")))
                        .file(new MockMultipartFile("files", "b.png", "image/png", encode(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png")))
                        .with(authHeaders.buyer(BUYER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertThat(reviewAttachmentCount(BUYER)).isZero();
    }

    @Test
    @DisplayName("P4 미연결 사진 20개 보유 → 추가 업로드 400·행 21개 아님")
    void upload_unlinkedCapReached_returns400() throws Exception {
        for (int index = 0; index < UNLINKED_CAP; index++) {
            jdbc.update("INSERT INTO attachment (public_id, target_type, target_id, file_name, file_path, display_order, uploaded_by, "
                            + "created_at, updated_at) VALUES (?, 'REVIEW', NULL, 's.jpg', ?, 0, ?, NOW(6), NOW(6))",
                    ReviewFixture.pid("att_", "RVCAP" + index + "X"), "/api/v1/files/reviews/cap" + index + ".jpg", BUYER);
        }

        mockMvc.perform(multipart(UPLOAD_URL).file(smallPng("more.png")).with(authHeaders.buyer(BUYER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertThat(reviewAttachmentCount(BUYER)).isEqualTo(UNLINKED_CAP);
    }

    @Test
    @DisplayName("P5 요청 크기 필터: 6MB+1 → 413 / 정확히 6MB → 필터 통과(파트 없음 400) / 청크 → 411 / 익명 → 401")
    void upload_requestSizeFilterAndAuth() throws Exception {
        mockMvc.perform(post(UPLOAD_URL).with(authHeaders.buyer(BUYER))
                        .contentType(MediaType.MULTIPART_FORM_DATA).content(new byte[(int) PHOTO_REQUEST_LIMIT + 1]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        mockMvc.perform(post(UPLOAD_URL).with(authHeaders.buyer(BUYER))
                        .contentType(MediaType.MULTIPART_FORM_DATA).content(new byte[(int) PHOTO_REQUEST_LIMIT]))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(UPLOAD_URL).with(authHeaders.buyer(BUYER))
                        .contentType(MediaType.MULTIPART_FORM_DATA).header(HttpHeaders.TRANSFER_ENCODING, "chunked"))
                .andExpect(status().isLengthRequired())
                .andExpect(jsonPath("$.code").value("LENGTH_REQUIRED"));
        mockMvc.perform(multipart(UPLOAD_URL).file(smallPng("anon.png")))
                .andExpect(status().isUnauthorized());

        assertThat(reviewAttachmentCount(BUYER)).isZero();
    }

    // ==================== 연결 ====================

    @Test
    @DisplayName("P6 작성 시 사진 [둘째, 첫째] 연결 → target_id = 리뷰·display_order 요청 순서")
    void create_withPhotos_linksInRequestOrder() throws Exception {
        String first = uploadOk(BUYER, smallPng("1.png")).get("attachmentId").asText();
        String second = uploadOk(BUYER, smallPng("2.png")).get("attachmentId").asText();

        mockMvc.perform(post(CREATE_URL).with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(itemPid, second, first)))
                .andExpect(status().isCreated());

        Long reviewId = jdbc.queryForObject("SELECT id FROM review WHERE order_item_id = ?", Long.class, ITEM);
        assertThat(targetIdOf(second)).isEqualTo(reviewId);
        assertThat(targetIdOf(first)).isEqualTo(reviewId);
        assertThat(jdbc.queryForObject("SELECT display_order FROM attachment WHERE public_id = ?", Integer.class, second)).isZero();
        assertThat(jdbc.queryForObject("SELECT display_order FROM attachment WHERE public_id = ?", Integer.class, first)).isEqualTo(1);
    }

    @Test
    @DisplayName("P7 연결 거부: 타인 사진·클레임 첨부(교차 유형)·다른 리뷰에 연결된 사진·중복 id → 400 / 6장 → 400 VALIDATION_FAILED · 리뷰 미생성")
    void create_withForbiddenPhotos_returns400() throws Exception {
        String others = uploadOk(OTHER_BUYER, smallPng("others.png")).get("attachmentId").asText();
        String claimPhoto = ReviewFixture.pid("att_", "RVCLAIMPHOTO");
        jdbc.update("INSERT INTO attachment (public_id, target_type, target_id, file_name, file_path, display_order, uploaded_by, "
                        + "created_at, updated_at) VALUES (?, 'CLAIM', NULL, 'c.png', '/api/v1/files/claims/rv-claim.png', 0, ?, NOW(6), NOW(6))",
                claimPhoto, BUYER);
        String used = uploadOk(BUYER, smallPng("used.png")).get("attachmentId").asText();
        mockMvc.perform(post(CREATE_URL).with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(itemPid, used)))
                .andExpect(status().isCreated());
        String mine = uploadOk(BUYER, smallPng("mine.png")).get("attachmentId").asText();

        for (String body : List.of(createBody(secondItemPid, others), createBody(secondItemPid, claimPhoto),
                createBody(secondItemPid, used), createBody(secondItemPid, mine, mine))) {
            mockMvc.perform(post(CREATE_URL).with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        mockMvc.perform(post(CREATE_URL).with(authHeaders.buyer(BUYER)).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(secondItemPid, mine, "att_a", "att_b", "att_c", "att_d", "att_e")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review WHERE order_item_id = ?", Integer.class, SECOND_ITEM)).isZero();
        assertThat(targetIdOf(mine)).isNull();
        assertThat(targetIdOf(claimPhoto)).isNull();
        assertThat(targetIdOf(others)).isNull();
    }

    // ==================== 정리 배치 ====================

    @Test
    @DisplayName("P8 정리 배치 REVIEW 확장: 25시간 경과 미연결 리뷰 사진 → 행·파일 삭제 / 연결된 25시간 리뷰 사진 → 보존")
    void cleanupBatch_includesReviewPhotos() throws Exception {
        LocalDateTime stale = LocalDateTime.now().minusHours(25);
        long staleUnlinkedId = SEED_ATTACHMENT_BASE;
        long staleLinkedId = SEED_ATTACHMENT_BASE + 1;
        String staleKey = "reviews/2026/09/rv-stale.png";
        String linkedKey = "reviews/2026/09/rv-linked.png";
        writeStoredFile(staleKey);
        writeStoredFile(linkedKey);
        seedReviewAttachment(staleUnlinkedId, null, "/api/v1/files/" + staleKey, stale);
        seedReviewAttachment(staleLinkedId, 999_106L, "/api/v1/files/" + linkedKey, stale);

        new AttachmentCleanupScheduler(attachmentRepository, attachmentCleanupService, imageUploadService).cleanupBatch();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attachment WHERE id = ?", Integer.class, staleUnlinkedId)).isZero();
        assertThat(Files.exists(uploadRoot.resolve(staleKey))).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attachment WHERE id = ?", Integer.class, staleLinkedId)).isEqualTo(1);
        assertThat(Files.exists(uploadRoot.resolve(linkedKey))).isTrue();
    }

    // ==================== helpers (모든 SQL은 ? 바인딩·문자열 concat 없음) ====================

    private JsonNode uploadOk(long buyerId, MockMultipartFile file) throws Exception {
        JsonNode item = upload(buyerId, file);
        assertThat(item.get("success").asBoolean()).as("업로드 성공: %s", item).isTrue();
        return item;
    }

    private JsonNode upload(long buyerId, MockMultipartFile file) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(UPLOAD_URL);
        request.file(file);
        String body = mockMvc.perform(request.with(authHeaders.buyer(buyerId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return jsonMapper.readTree(body).get("results").get(0);
    }

    private static String createBody(String orderItemPid, String... attachmentIds) {
        String ids = String.join(",", java.util.Arrays.stream(attachmentIds).map(id -> "\"" + id + "\"").toList());
        return "{\"orderItemId\":\"" + orderItemPid + "\",\"rating\":4,\"content\":\"사진 리뷰\",\"attachmentIds\":[" + ids + "]}";
    }

    private Long targetIdOf(String attachmentPublicId) {
        return jdbc.queryForObject("SELECT target_id FROM attachment WHERE public_id = ?", Long.class, attachmentPublicId);
    }

    private int reviewAttachmentCount(long uploaderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM attachment WHERE target_type = 'REVIEW' AND uploaded_by = ?", Integer.class,
                uploaderId);
    }

    private byte[] storedBytes(String url) throws IOException {
        return Files.readAllBytes(uploadRoot.resolve(url.substring("/api/v1/files/".length())));
    }

    private void writeStoredFile(String relativeKey) throws IOException {
        Path target = uploadRoot.resolve(relativeKey);
        Files.createDirectories(target.getParent());
        Files.write(target, encode(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png"));
    }

    private void seedReviewAttachment(long id, Long targetId, String filePath, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO attachment (id, public_id, target_type, target_id, file_name, file_path, display_order, uploaded_by, "
                        + "created_at, updated_at) VALUES (?, ?, 'REVIEW', ?, 'seed.png', ?, 0, ?, ?, ?)",
                id, ReviewFixture.pid("att_", "RVSEED" + id), targetId, filePath, BUYER, createdAt, createdAt);
    }

    private static MockMultipartFile smallPng(String name) throws IOException {
        return new MockMultipartFile("files", name, "image/png", encode(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png"));
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    /** 무작위 256색 팔레트 + 무작위 인덱스(압축되지 않음) PNG. 픽셀당 1바이트로 저장되지만 트루컬러로 다시 쓰면 3바이트가 된다. */
    private static byte[] randomPalettePng(int width, int height) throws IOException {
        java.util.Random random = new java.util.Random(106L);
        byte[] reds = new byte[256];
        byte[] greens = new byte[256];
        byte[] blues = new byte[256];
        random.nextBytes(reds);
        random.nextBytes(greens);
        random.nextBytes(blues);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED,
                new java.awt.image.IndexColorModel(8, 256, reds, greens, blues));
        byte[] indices = new byte[width * height];
        random.nextBytes(indices);
        image.getRaster().setDataElements(0, 0, width, height, indices);
        return encode(image, "png");
    }

    private static byte[] leftRedRightBlueJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, width / 2, height);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(width / 2, 0, width - width / 2, height);
        } finally {
            graphics.dispose();
        }
        return encode(image, "jpg");
    }

    /**
     * PNG 시그니처 + IHDR(가로·세로·8비트 RGB) + 빈 IDAT + IEND — reader가 IDAT 앞까지 읽어 밴드·샘플 비트를 헤더 단계에서 확정하고(디코딩
     * 예산 추정 가능), 픽셀 데이터가 없어 실제 디코딩은 실패한다(UploadHardeningIntegrationTest 픽스처와 같은 구성).
     */
    private static byte[] pngHeaderOnly(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] ihdr = {(byte) (width >>> 24), (byte) (width >>> 16), (byte) (width >>> 8), (byte) width,
                (byte) (height >>> 24), (byte) (height >>> 16), (byte) (height >>> 8), (byte) height, 8, 2, 0, 0, 0};
        writeChunk(output, "IHDR", ihdr);
        writeChunk(output, "IDAT", new byte[0]);
        writeChunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream output, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        output.write(new byte[] {(byte) (data.length >>> 24), (byte) (data.length >>> 16), (byte) (data.length >>> 8), (byte) data.length});
        output.write(typeBytes);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        long value = crc.getValue();
        output.write(new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
    }

    /**
     * JPEG의 APP0(JFIF) 뒤에 APP1 Exif를 끼운다. TIFF(빅엔디언) IFD0 = Orientation(SHORT) + GPS IFD 포인터, GPS IFD = GPSLatitudeRef "N".
     */
    private static byte[] withExif(byte[] jpeg, int orientation) throws IOException {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.write(new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8});
        int gpsIfdOffset = 8 + 2 + 2 * 12 + 4;
        tiff.write(new byte[] {0, 2});
        tiff.write(new byte[] {0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0});
        tiff.write(new byte[] {(byte) 0x88, 0x25, 0, 4, 0, 0, 0, 1, 0, 0, 0, (byte) gpsIfdOffset});
        tiff.write(new byte[] {0, 0, 0, 0});
        tiff.write(new byte[] {0, 1});
        tiff.write(new byte[] {0, 1, 0, 2, 0, 0, 0, 2, 'N', 0, 0, 0});
        tiff.write(new byte[] {0, 0, 0, 0});
        byte[] exifHeader = {'E', 'x', 'i', 'f', 0, 0};
        int segmentLength = 2 + exifHeader.length + tiff.size();

        int app0End = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(jpeg, 0, app0End);
        output.write(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (segmentLength >>> 8), (byte) segmentLength});
        output.write(exifHeader);
        tiff.writeTo(output);
        output.write(jpeg, app0End, jpeg.length - app0End);
        return output.toByteArray();
    }

    /** PNG의 IEND(마지막 12바이트) 앞에 tEXt 청크를 끼운다. */
    private static byte[] withTextChunk(byte[] png, String text) throws IOException {
        byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        int iendStart = png.length - 12;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(png, 0, iendStart);
        output.write(new byte[] {(byte) (data.length >>> 24), (byte) (data.length >>> 16), (byte) (data.length >>> 8), (byte) data.length});
        output.write(type);
        output.write(data);
        long value = crc.getValue();
        output.write(new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
        output.write(png, iendStart, 12);
        return output.toByteArray();
    }

    /** TIFF(빅엔디언) IFD0 = Make "ZS" · Model "M1" · Orientation 1 · GPS IFD 포인터 / GPS IFD = GPSLatitudeRef "N". 값은 모두 4바이트 이하(인라인). */
    private static byte[] deviceAndGpsTiff() throws IOException {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.write(new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8});
        int gpsIfdOffset = 8 + 2 + 4 * 12 + 4;
        tiff.write(new byte[] {0, 4});
        tiff.write(new byte[] {0x01, 0x0F, 0, 2, 0, 0, 0, 3, 'Z', 'S', 0, 0});
        tiff.write(new byte[] {0x01, 0x10, 0, 2, 0, 0, 0, 3, 'M', '1', 0, 0});
        tiff.write(new byte[] {0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 1, 0, 0});
        tiff.write(new byte[] {(byte) 0x88, 0x25, 0, 4, 0, 0, 0, 1, 0, 0, 0, (byte) gpsIfdOffset});
        tiff.write(new byte[] {0, 0, 0, 0});
        tiff.write(new byte[] {0, 1});
        tiff.write(new byte[] {0, 1, 0, 2, 0, 0, 0, 2, 'N', 0, 0, 0});
        tiff.write(new byte[] {0, 0, 0, 0});
        return tiff.toByteArray();
    }

    /** Photoshop 이미지 리소스 1개(8BIM · 0x0404 IPTC-NAA) — IPTC 레코드 2:120(캡션). */
    private static byte[] iptcResource() throws IOException {
        byte[] caption = "caption".getBytes(StandardCharsets.US_ASCII);
        byte[] iptc = concat(new byte[] {0x1C, 0x02, 0x78, 0, (byte) caption.length}, caption);
        ByteArrayOutputStream resource = new ByteArrayOutputStream();
        resource.write("8BIM".getBytes(StandardCharsets.US_ASCII));
        resource.write(new byte[] {0x04, 0x04, 0, 0});
        resource.write(new byte[] {0, 0, 0, (byte) iptc.length});
        resource.write(iptc);
        if (iptc.length % 2 == 1) {
            resource.write(0);
        }
        return resource.toByteArray();
    }

    private static byte[] jpegSegment(int marker, byte[] payload) {
        int length = payload.length + 2;
        return concat(new byte[] {(byte) 0xFF, (byte) marker, (byte) (length >>> 8), (byte) length}, payload);
    }

    /** JPEG의 APP0(JFIF) 바로 뒤에 세그먼트들을 끼운다. */
    private static byte[] withJpegSegments(byte[] jpeg, List<byte[]> segments) throws IOException {
        int app0End = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(jpeg, 0, app0End);
        for (byte[] segment : segments) {
            output.write(segment);
        }
        output.write(jpeg, app0End, jpeg.length - app0End);
        return output.toByteArray();
    }

    /** JPEG 마커를 SOI부터 SOS 전까지 구조로 읽어 APPn 세그먼트를 "APPn:식별자"로 돌려준다(식별자 = 페이로드 첫 NUL 앞 ASCII). */
    private static List<String> jpegAppSegments(byte[] jpeg) {
        assertThat(jpeg[0] & 0xFF).isEqualTo(0xFF);
        assertThat(jpeg[1] & 0xFF).isEqualTo(JPEG_SOI);
        List<String> appSegments = new java.util.ArrayList<>();
        int position = 2;
        while (position + 4 <= jpeg.length) {
            assertThat(jpeg[position] & 0xFF).as("마커 시작 위치 %d", position).isEqualTo(0xFF);
            int marker = jpeg[position + 1] & 0xFF;
            if (marker == JPEG_SOS || marker == JPEG_EOI) {
                break;
            }
            int length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
            if (marker >= JPEG_APP0 && marker <= JPEG_APP15) {
                int payloadStart = position + 4;
                int identifierEnd = payloadStart;
                while (identifierEnd < position + 2 + length && jpeg[identifierEnd] != 0) {
                    identifierEnd++;
                }
                appSegments.add("APP" + (marker - JPEG_APP0) + ":"
                        + new String(jpeg, payloadStart, identifierEnd - payloadStart, StandardCharsets.US_ASCII));
            }
            position += 2 + length;
        }
        return appSegments;
    }

    /** PNG 청크를 시그니처 뒤부터 IEND까지 구조로 읽어 청크 유형 목록을 돌려준다. */
    private static List<String> pngChunkTypes(byte[] png) {
        List<String> types = new java.util.ArrayList<>();
        int position = PNG_SIGNATURE_LENGTH;
        while (position + PNG_CHUNK_OVERHEAD <= png.length) {
            int length = ((png[position] & 0xFF) << 24) | ((png[position + 1] & 0xFF) << 16) | ((png[position + 2] & 0xFF) << 8)
                    | (png[position + 3] & 0xFF);
            String type = new String(png, position + 4, 4, StandardCharsets.US_ASCII);
            types.add(type);
            if ("IEND".equals(type)) {
                break;
            }
            position += PNG_CHUNK_OVERHEAD + length;
        }
        return types;
    }

    /** PNG의 IHDR 청크 바로 뒤에 청크 바이트를 끼운다(메타 청크는 IDAT 앞이 규격 위치). */
    private static byte[] insertAfterHeader(byte[] png, byte[] chunks) throws IOException {
        int ihdrEnd = PNG_SIGNATURE_LENGTH + PNG_CHUNK_OVERHEAD + PNG_IHDR_LENGTH;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(png, 0, ihdrEnd);
        output.write(chunks);
        output.write(png, ihdrEnd, png.length - ihdrEnd);
        return output.toByteArray();
    }

    private static byte[] deflate(String text) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream deflater = new java.util.zip.DeflaterOutputStream(output)) {
            deflater.write(text.getBytes(StandardCharsets.ISO_8859_1));
        }
        return output.toByteArray();
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] joined = java.util.Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, joined, head.length, tail.length);
        return joined;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[start + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return start;
        }
        return -1;
    }
}
