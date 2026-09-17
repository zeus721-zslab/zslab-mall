package com.zslab.mall.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.common.security.TokenProvider;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 클레임 첨부 인가 서빙 통합 테스트(Track 82 D-176·실 MariaDB·임시 업로드 루트). claim(요청자 OWNER)·attachment 행·파일을 직접 심고
 * GET /api/v1/files/claims/** 를 Bearer·admin_token·auth_token 후보별로 호출해 열람 규칙(연결: claim.requested_by·ADMIN / 미연결: 업로더만 /
 * 셀러 거부 / _thumb 동일·후보 정확히 1건)과 거부 404 통일·캐시 금지 헤더·쿠키 비인식 경계(그 외 경로·메서드)·인코딩 경로를 검증한다.
 * 연결 첨부의 uploaded_by는 클레임 요청자와 다른 UPLOADER_ID로 심어 판정 기준이 claim.requested_by임을 드러낸다(외부 검토 반영).
 * claim 시드는 order_item FK 때문에 FOREIGN_KEY_CHECKS=0 TX에서 하고 try-finally로 =1 복원한다.
 */
@AutoConfigureMockMvc
class ClaimAttachmentServingIntegrationTest extends AbstractIntegrationTest {

    private static final long OWNER_ID = 82101L;
    private static final long OTHER_BUYER_ID = 82102L;
    private static final long ADMIN_ID = 82103L;
    /** 연결 첨부를 올린 사람(≠ 클레임 요청자). 연결 첨부 판정에 uploaded_by가 쓰이지 않음을 검증한다. */
    private static final long UPLOADER_ID = 82104L;
    private static final long CLAIM_ID = 82100L;
    private static final long ORDER_ITEM_ID = 82100L;
    private static final long LINKED_ATTACHMENT_ID = 82101L;
    private static final long UNLINKED_ATTACHMENT_ID = 82102L;
    private static final long TWIN_ATTACHMENT_ID = 82103L;
    private static final String LINKED_KEY = "claims/2026/09/01T82LINKED0000000000000001.png";
    private static final String LINKED_THUMB_KEY = "claims/2026/09/01T82LINKED0000000000000001_thumb.png";
    private static final String LINKED_WEBP_TWIN_KEY = "claims/2026/09/01T82LINKED0000000000000001.webp";
    private static final String LINKED_THUMB_UNSUPPORTED_KEY = "claims/2026/09/01T82LINKED0000000000000001_thumb.gif";
    private static final String LINKED_KEY_ENCODED = "%63laims/2026/09/01T82LINKED0000000000000001.png";
    private static final String UNLINKED_KEY = "claims/2026/09/01T82UNLINKED00000000000001.png";
    private static final String NO_ROW_KEY = "claims/2026/09/01T82NOROW0000000000000001.png";
    private static final String PRODUCT_KEY = "products/2026/09/01T82PRODUCT000000000000001.png";
    private static final String FILES_URL = "/api/v1/files/";
    private static final String NO_STORE_PRIVATE = "no-store, private";
    private static final String INVALID_TOKEN = "not.a.jwt";

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
    private TokenProvider tokenProvider;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() throws IOException {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedClaim();
        seedAttachment(LINKED_ATTACHMENT_ID, UPLOADER_ID, CLAIM_ID, FILES_URL + LINKED_KEY);
        seedAttachment(UNLINKED_ATTACHMENT_ID, OWNER_ID, null, FILES_URL + UNLINKED_KEY);
        writeStoredFile(LINKED_KEY);
        writeStoredFile(LINKED_THUMB_KEY);
        writeStoredFile(UNLINKED_KEY);
        writeStoredFile(NO_ROW_KEY); // 행 없는 파일(고아)도 서빙되지 않아야 한다
        writeStoredFile(PRODUCT_KEY);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("연결 첨부: 익명 → 404 FILE_NOT_FOUND / 타 구매자 Bearer → 404 / 클레임 요청자 Bearer → 200 / auth_token 쿠키만 → 200 / uploaded_by 주체(≠requested_by) → 404")
    void linkedAttachment_claimRequesterOrNothing() throws Exception {
        expectNotFound(get(FILES_URL + LINKED_KEY));
        expectNotFound(get(FILES_URL + LINKED_KEY).headers(authHeaders.buyer(OTHER_BUYER_ID)));
        expectOk(get(FILES_URL + LINKED_KEY).headers(authHeaders.buyer(OWNER_ID)));
        expectOk(get(FILES_URL + LINKED_KEY).cookie(authCookie(OWNER_ID, ActorRole.BUYER)));
        expectNotFound(get(FILES_URL + LINKED_KEY).cookie(authCookie(OTHER_BUYER_ID, ActorRole.BUYER)));
        expectNotFound(get(FILES_URL + LINKED_KEY).headers(authHeaders.buyer(UPLOADER_ID)));
        expectNotFound(get(FILES_URL + LINKED_KEY).cookie(authCookie(UPLOADER_ID, ActorRole.BUYER)));
    }

    @Test
    @DisplayName("연결 첨부: 대상 클레임 행 없음 → 요청자·ADMIN 모두 404")
    void linkedAttachment_missingClaim_rejected() throws Exception {
        jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_ID);
        expectNotFound(get(FILES_URL + LINKED_KEY).headers(authHeaders.buyer(OWNER_ID)));
        expectNotFound(get(FILES_URL + LINKED_KEY).headers(authHeaders.admin(ADMIN_ID)));
    }

    @Test
    @DisplayName("ADMIN admin_token 쿠키: 연결 첨부 → 200 / 미연결 첨부 → 404 (ADMIN도 미연결은 불가)")
    void admin_linkedOnly() throws Exception {
        expectOk(get(FILES_URL + LINKED_KEY).cookie(adminCookie(ADMIN_ID)));
        expectOk(get(FILES_URL + LINKED_KEY).headers(authHeaders.admin(ADMIN_ID)));
        expectNotFound(get(FILES_URL + UNLINKED_KEY).cookie(adminCookie(ADMIN_ID)));
        expectNotFound(get(FILES_URL + UNLINKED_KEY).headers(authHeaders.admin(ADMIN_ID)));
    }

    @Test
    @DisplayName("미연결 첨부: 업로더 본인 → 200 / 타 구매자 → 404")
    void unlinkedAttachment_uploaderOnly() throws Exception {
        expectOk(get(FILES_URL + UNLINKED_KEY).headers(authHeaders.buyer(OWNER_ID)));
        expectOk(get(FILES_URL + UNLINKED_KEY).cookie(authCookie(OWNER_ID, ActorRole.BUYER)));
        expectNotFound(get(FILES_URL + UNLINKED_KEY).headers(authHeaders.buyer(OTHER_BUYER_ID)));
    }

    @Test
    @DisplayName("셀러 JWT(같은 user id라도 SELLER role) → 연결·미연결 모두 404")
    void seller_rejected() throws Exception {
        expectNotFound(get(FILES_URL + LINKED_KEY).headers(authHeaders.seller(OWNER_ID)));
        expectNotFound(get(FILES_URL + UNLINKED_KEY).headers(authHeaders.seller(OWNER_ID)));
        expectNotFound(get(FILES_URL + LINKED_KEY).cookie(authCookie(OWNER_ID, ActorRole.SELLER)));
    }

    @Test
    @DisplayName("후보 독립 평가: 무효 admin_token + 유효 소유자 auth_token → 200 / 무효 Bearer + 유효 auth_token → 200(401 아님) / 무효만 → 404")
    void candidates_evaluatedIndependently() throws Exception {
        expectOk(get(FILES_URL + LINKED_KEY)
                .cookie(new Cookie("admin_token", INVALID_TOKEN), authCookie(OWNER_ID, ActorRole.BUYER)));
        expectOk(get(FILES_URL + LINKED_KEY)
                .header("Authorization", "Bearer " + INVALID_TOKEN)
                .cookie(authCookie(OWNER_ID, ActorRole.BUYER)));
        expectNotFound(get(FILES_URL + LINKED_KEY)
                .header("Authorization", "Bearer " + INVALID_TOKEN)
                .cookie(new Cookie("admin_token", INVALID_TOKEN), new Cookie("auth_token", INVALID_TOKEN)));
    }

    @Test
    @DisplayName("_thumb 키: 원본 행 규칙 그대로 — 요청자 200·ADMIN 200·타인 404·익명 404 / 지원하지 않는 썸네일 확장자(.gif) → 404 / 원본 후보 2건(png·webp 동시 존재) → 404")
    void thumbnail_followsOriginalRow_exactlyOneCandidate() throws Exception {
        expectOk(get(FILES_URL + LINKED_THUMB_KEY).headers(authHeaders.buyer(OWNER_ID)));
        expectOk(get(FILES_URL + LINKED_THUMB_KEY).cookie(adminCookie(ADMIN_ID)));
        expectNotFound(get(FILES_URL + LINKED_THUMB_KEY).headers(authHeaders.buyer(OTHER_BUYER_ID)));
        expectNotFound(get(FILES_URL + LINKED_THUMB_KEY));

        writeStoredFile(LINKED_THUMB_UNSUPPORTED_KEY); // 파일이 있어도 인가 단계에서 거부
        expectNotFound(get(FILES_URL + LINKED_THUMB_UNSUPPORTED_KEY).headers(authHeaders.buyer(OWNER_ID)));

        // 같은 base의 webp 원본 행이 추가되면 _thumb.png 후보가 2건 → 정확히 1건이 아니므로 요청자·ADMIN 모두 404(원본 png 자체는 영향 없음)
        seedAttachment(TWIN_ATTACHMENT_ID, UPLOADER_ID, CLAIM_ID, FILES_URL + LINKED_WEBP_TWIN_KEY);
        expectNotFound(get(FILES_URL + LINKED_THUMB_KEY).headers(authHeaders.buyer(OWNER_ID)));
        expectNotFound(get(FILES_URL + LINKED_THUMB_KEY).cookie(adminCookie(ADMIN_ID)));
        expectOk(get(FILES_URL + LINKED_KEY).headers(authHeaders.buyer(OWNER_ID)));
    }

    @Test
    @DisplayName("후보 조합: 유효하지만 권한 없는 타 구매자 Bearer + 요청자 auth_token → 200 / 미연결: 유효 admin_token + 업로더 auth_token → 200")
    void candidates_validButUnauthorizedDoesNotBlockOthers() throws Exception {
        expectOk(get(FILES_URL + LINKED_KEY).headers(authHeaders.buyer(OTHER_BUYER_ID)).cookie(authCookie(OWNER_ID, ActorRole.BUYER)));
        expectOk(get(FILES_URL + UNLINKED_KEY).cookie(adminCookie(ADMIN_ID), authCookie(OWNER_ID, ActorRole.BUYER)));
    }

    @Test
    @DisplayName("인코딩 경로 GET /api/v1/files/%63laims/... + 무효 Bearer + 유효 요청자 쿠키 → 정상 경로와 동일 200 / 익명 → 404 (필터 skip·permitAll·컨트롤러 분기가 디코딩 경로로 일치)")
    void encodedPath_sameAsPlainPath() throws Exception {
        // 문자열 템플릿은 MockMvc가 %를 다시 인코딩(%2563)해 firewall 400이 되므로 URI 객체로 raw 경로를 그대로 보낸다.
        expectOk(get(URI.create(FILES_URL + LINKED_KEY_ENCODED))
                .header("Authorization", "Bearer " + INVALID_TOKEN)
                .cookie(authCookie(OWNER_ID, ActorRole.BUYER)));
        expectNotFound(get(URI.create(FILES_URL + LINKED_KEY_ENCODED)));
    }

    @Test
    @DisplayName("POST /api/v1/files/claims/... + 요청자 쿠키만 → 401 UNAUTHENTICATED (쿠키 인식은 GET 매처 한정·POST는 필터 경유·anyRequest authenticated)")
    void postOnClaimServingPath_cookieNotRecognized() throws Exception {
        mockMvc.perform(post(FILES_URL + LINKED_KEY).cookie(authCookie(OWNER_ID, ActorRole.BUYER)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("미존재: 행 없는 파일(고아) → 404 / 행은 있고 파일 없음 → 404 / 200·404 모두 Cache-Control no-store, private 단일 헤더")
    void notFound_andCacheHeaders() throws Exception {
        expectNotFound(get(FILES_URL + NO_ROW_KEY).headers(authHeaders.admin(ADMIN_ID)));
        Files.delete(uploadRoot.resolve(UNLINKED_KEY));
        expectNotFound(get(FILES_URL + UNLINKED_KEY).headers(authHeaders.buyer(OWNER_ID)));

        assertThat(mockMvc.perform(get(FILES_URL + LINKED_KEY).headers(authHeaders.buyer(OWNER_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeaders("Cache-Control"))
                .containsExactly(NO_STORE_PRIVATE);
        assertThat(mockMvc.perform(get(FILES_URL + LINKED_KEY))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getHeaders("Cache-Control"))
                .containsExactly(NO_STORE_PRIVATE);
    }

    @Test
    @DisplayName("쿠키 비인식 경계: auth_token 쿠키만으로 POST /api/v1/claims/attachments → 401 UNAUTHENTICATED(그 외 경로는 Bearer만) / products 익명 → 200 public immutable")
    void cookieOnlyOutsideClaimServing_isNotAuthenticated() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "photo.png", "image/png", png(10, 10));
        mockMvc.perform(multipart("/api/v1/claims/attachments").file(file).cookie(authCookie(OWNER_ID, ActorRole.BUYER)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get(FILES_URL + PRODUCT_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
    }

    // ==================== helpers ====================

    private ResultActions expectOk(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", NO_STORE_PRIVATE));
    }

    private ResultActions expectNotFound(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"))
                .andExpect(header().string("Cache-Control", NO_STORE_PRIVATE));
    }

    private Cookie authCookie(long actorId, ActorRole role) {
        return new Cookie("auth_token", tokenProvider.issue(actorId, role));
    }

    private Cookie adminCookie(long actorId) {
        return new Cookie("admin_token", tokenProvider.issue(actorId, ActorRole.ADMIN));
    }

    /** order_item FK는 FOREIGN_KEY_CHECKS=0으로 우회(클레임 요청자만 필요). try-finally로 =1 복원. */
    private void seedClaim() {
        tx.executeWithoutResult(status -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, reason_detail, status, "
                                + "previous_order_item_status, requested_by, requested_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'RETURN', 'PRODUCT_DEFECT', 'T82', 'REQUESTED', 'DELIVERED', ?, NOW(6), NOW(6), NOW(6))",
                        CLAIM_ID, "clm_" + ("T82CLM" + CLAIM_ID + "00000000000000000000000000").substring(0, 26), ORDER_ITEM_ID, OWNER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedAttachment(long id, long uploadedBy, Long targetId, String filePath) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO attachment (id, public_id, target_type, target_id, file_name, file_path, mime_type, file_size, "
                        + "display_order, uploaded_by, created_at, updated_at) "
                        + "VALUES (?, ?, 'CLAIM', ?, 'seed.png', ?, 'image/png', 1, 0, ?, ?, ?)",
                id, "att_" + ("T82ATT" + id + "00000000000000000000000000").substring(0, 26), targetId, filePath, uploadedBy,
                now, now);
    }

    private void writeStoredFile(String relativeKey) throws IOException {
        Path target = uploadRoot.resolve(relativeKey);
        Files.createDirectories(target.getParent());
        Files.write(target, png(10, 10));
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM attachment WHERE uploaded_by IN (?, ?, ?)", OWNER_ID, OTHER_BUYER_ID, UPLOADER_ID);
        jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_ID);
    }
}
