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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
 * 클레임 첨부 인가 서빙 통합 테스트(Track 82 D-176·D-235 PR3 K5·실 MariaDB·임시 업로드 루트). claim(요청자 OWNER)·attachment 행·파일을 직접 심고
 * 구매자 경로 GET /api/v1/files/claims/**(구매자 역할 쿠키)와 셀러·관리자 별칭(/api/v1/{seller|admin}/files/claims/**)으로 열람 규칙(연결:
 * claim.requested_by·ADMIN / 미연결: 업로더만 / 셀러는 품목 소유 셀러만(Track 90-D-1·소속 없음·타 셀러 거부) / _thumb 동일·후보 정확히 1건)과
 * 익명·회원 상태 거부 401 · 열람 권한 없음·미존재 404 통일·캐시 금지 헤더·인코딩 경로를 검증한다.
 * 연결 첨부의 uploaded_by는 클레임 요청자와 다른 UPLOADER_ID로 심어 판정 기준이 claim.requested_by임을 드러낸다(외부 검토 반영).
 * claim·order_item 시드는 FK 때문에 FOREIGN_KEY_CHECKS=0 TX에서 하고 try-finally로 =1 복원한다. 품목 ORDER_ITEM_ID는 셀러 A 소유이며 셀러 B는 타 셀러다.
 */
@AutoConfigureMockMvc
class ClaimAttachmentServingIntegrationTest extends AbstractIntegrationTest {

    private static final long OWNER_ID = 82101L;
    private static final long OTHER_BUYER_ID = 82102L;
    private static final long ADMIN_ID = 82103L;
    /** 연결 첨부를 올린 사람(≠ 클레임 요청자). 연결 첨부 판정에 uploaded_by가 쓰이지 않음을 검증한다. */
    private static final long UPLOADER_ID = 82104L;
    /** 품목 소유 셀러 A의 구성원 user·seller(Track 90-D-1). */
    private static final long SELLER_A_USER_ID = 82105L;
    private static final long SELLER_A_ID = 82100L;
    /** 타 셀러 B의 구성원 user·seller. */
    private static final long SELLER_B_USER_ID = 82106L;
    private static final long SELLER_B_ID = 82101L;
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
    private static final String SELLER_FILES_URL = "/api/v1/seller/files/";
    private static final String ADMIN_FILES_URL = "/api/v1/admin/files/";
    private static final String NO_STORE_PRIVATE = "no-store, private";

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
        seedSellers();
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
    @DisplayName("연결 첨부(구매자 경로): 익명 → 401 / 타 구매자 → 404 / 클레임 요청자 → 200 / uploaded_by 주체(≠requested_by) → 404")
    void linkedAttachment_claimRequesterOrNothing() throws Exception {
        mockMvc.perform(get(FILES_URL + LINKED_KEY)).andExpect(status().isUnauthorized());
        expectNotFound(get(FILES_URL + LINKED_KEY).with(authHeaders.buyer(OTHER_BUYER_ID)));
        expectOk(get(FILES_URL + LINKED_KEY).with(authHeaders.buyer(OWNER_ID)));
        expectNotFound(get(FILES_URL + LINKED_KEY).with(authHeaders.buyer(UPLOADER_ID)));
    }

    @Test
    @DisplayName("연결 첨부: 대상 클레임 행 없음 → 요청자(구매자 경로)·ADMIN(관리자 별칭) 모두 404")
    void linkedAttachment_missingClaim_rejected() throws Exception {
        jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_ID);
        expectNotFound(get(FILES_URL + LINKED_KEY).with(authHeaders.buyer(OWNER_ID)));
        expectNotFound(get(ADMIN_FILES_URL + LINKED_KEY).with(authHeaders.admin(ADMIN_ID)));
    }

    @Test
    @DisplayName("ADMIN(관리자 별칭): 연결 첨부 → 200 / 미연결 첨부 → 404 (ADMIN도 미연결은 불가)")
    void admin_linkedOnly() throws Exception {
        expectOk(get(ADMIN_FILES_URL + LINKED_KEY).with(authHeaders.admin(ADMIN_ID)));
        expectNotFound(get(ADMIN_FILES_URL + UNLINKED_KEY).with(authHeaders.admin(ADMIN_ID)));
    }

    @Test
    @DisplayName("미연결 첨부: 업로더 본인 → 200 / 타 구매자 → 404")
    void unlinkedAttachment_uploaderOnly() throws Exception {
        expectOk(get(FILES_URL + UNLINKED_KEY).with(authHeaders.buyer(OWNER_ID)));
        expectNotFound(get(FILES_URL + UNLINKED_KEY).with(authHeaders.buyer(OTHER_BUYER_ID)));
    }

    @Test
    @DisplayName("셀러 본인(셀러 별칭·품목 seller_id 소속·Track 90-D-1): 연결 첨부 → 200 / _thumb → 200 / 미연결 → 404(어떤 클레임에도 속하지 않음)")
    void seller_owningItem_allowedForLinkedOnly() throws Exception {
        expectOk(get(SELLER_FILES_URL + LINKED_KEY).with(authHeaders.seller(SELLER_A_USER_ID)));
        expectOk(get(SELLER_FILES_URL + LINKED_THUMB_KEY).with(authHeaders.seller(SELLER_A_USER_ID)));
        expectNotFound(get(SELLER_FILES_URL + UNLINKED_KEY).with(authHeaders.seller(SELLER_A_USER_ID)));
    }

    @Test
    @DisplayName("셀러 소속·상태 fail-closed(셀러 별칭·외부 검토 r1·Q1): seller soft-delete → 404 / TERMINATED·PENDING → 404 / SUSPENDED → 200(조회 허용·D-190) / seller_user 행 제거 → 404 / 품목 seller_id 변경 → 404")
    void seller_membershipAndStatus_failClosed() throws Exception {
        String url = SELLER_FILES_URL + LINKED_KEY;
        // seller soft-delete: findMembershipByUserId가 seller를 조인(@SQLRestriction deleted_at IS NULL)하므로 empty → 거부
        jdbc.update("UPDATE seller SET deleted_at = NOW(6) WHERE id = ?", SELLER_A_ID);
        expectNotFound(get(url).with(authHeaders.seller(SELLER_A_USER_ID)));
        jdbc.update("UPDATE seller SET deleted_at = NULL WHERE id = ?", SELLER_A_ID);
        expectOk(get(url).with(authHeaders.seller(SELLER_A_USER_ID)));
        // 세션 불허 상태(SellerAccessPolicy.isSessionAllowed=false)
        for (String status : new String[] {"TERMINATED", "PENDING"}) {
            jdbc.update("UPDATE seller SET status = ? WHERE id = ?", status, SELLER_A_ID);
            expectNotFound(get(url).with(authHeaders.seller(SELLER_A_USER_ID)));
        }
        // SUSPENDED는 조회 세션이 유효(정지 셀러는 조회만 가능) → 열람 허용
        jdbc.update("UPDATE seller SET status = 'SUSPENDED' WHERE id = ?", SELLER_A_ID);
        expectOk(get(url).with(authHeaders.seller(SELLER_A_USER_ID)));
        jdbc.update("UPDATE seller SET status = 'ACTIVE' WHERE id = ?", SELLER_A_ID);
        // 구성원 관계 제거(seller_user에는 deleted_at이 없어 물리 삭제가 곧 탈퇴)
        jdbc.update("DELETE FROM seller_user WHERE user_id = ?", SELLER_A_USER_ID);
        expectNotFound(get(url).with(authHeaders.seller(SELLER_A_USER_ID)));
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", SELLER_A_USER_ID, SELLER_A_ID);
        expectOk(get(url).with(authHeaders.seller(SELLER_A_USER_ID)));
        // 품목이 다른 셀러 소유로 바뀌면(order_item에는 soft-delete가 없다) 소유 셀러였던 A도 거부
        tx.executeWithoutResult(status -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                jdbc.update("UPDATE order_item SET seller_id = ? WHERE id = ?", SELLER_B_ID, ORDER_ITEM_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
        expectNotFound(get(url).with(authHeaders.seller(SELLER_A_USER_ID)));
        expectOk(get(url).with(authHeaders.seller(SELLER_B_USER_ID)));
    }

    @Test
    @DisplayName("첨부 soft-delete(외부 검토 r1·Q1): deleted_at 설정 시 구매자 소유자·ADMIN·소유 셀러 모두 404(@SQLRestriction으로 행 자체가 조회되지 않음) · claim에는 soft-delete 컬럼이 없어 대상 아님")
    void softDeletedAttachment_rejectedForAllRoles() throws Exception {
        jdbc.update("UPDATE attachment SET deleted_at = NOW(6) WHERE id = ?", LINKED_ATTACHMENT_ID);
        expectNotFound(get(FILES_URL + LINKED_KEY).with(authHeaders.buyer(OWNER_ID)));
        expectNotFound(get(ADMIN_FILES_URL + LINKED_KEY).with(authHeaders.admin(ADMIN_ID)));
        expectNotFound(get(SELLER_FILES_URL + LINKED_KEY).with(authHeaders.seller(SELLER_A_USER_ID)));
        expectNotFound(get(ADMIN_FILES_URL + LINKED_THUMB_KEY).with(authHeaders.admin(ADMIN_ID)));
        jdbc.update("UPDATE attachment SET deleted_at = NULL WHERE id = ?", LINKED_ATTACHMENT_ID);
        expectOk(get(ADMIN_FILES_URL + LINKED_KEY).with(authHeaders.admin(ADMIN_ID)));
    }

    @Test
    @DisplayName("회원 상태(AuthenticatedUserStateVerifier·필터): 탈퇴(withdrawn_at)·삭제(deleted_at) 셀러 user → 401 / credentials_changed_at 이전 발급 토큰 → 401·이후 발급 → 200 / 탈퇴한 구매자 소유자 → 401")
    void userState_rejectedOnAttachmentPath() throws Exception {
        String sellerUrl = SELLER_FILES_URL + LINKED_KEY;
        expectOk(get(sellerUrl).with(authHeaders.seller(SELLER_A_USER_ID)));
        jdbc.update("UPDATE `user` SET withdrawn_at = NOW(6) WHERE id = ?", SELLER_A_USER_ID);
        mockMvc.perform(get(sellerUrl).with(authHeaders.seller(SELLER_A_USER_ID))).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE `user` SET withdrawn_at = NULL, deleted_at = NOW(6) WHERE id = ?", SELLER_A_USER_ID);
        mockMvc.perform(get(sellerUrl).with(authHeaders.seller(SELLER_A_USER_ID))).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE `user` SET deleted_at = NULL WHERE id = ?", SELLER_A_USER_ID);
        // 자격증명 갱신: 갱신 시각보다 iat가 앞선 토큰은 거부. DB 세션 NOW()와 JVM(Asia/Seoul) 벽시계가 다를 수 있어 경계는 고정 시각(먼 미래·먼 과거)으로 둔다.
        jdbc.update("UPDATE `user` SET credentials_changed_at = '2099-01-01 00:00:00' WHERE id = ?", SELLER_A_USER_ID);
        mockMvc.perform(get(sellerUrl).with(authHeaders.seller(SELLER_A_USER_ID))).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE `user` SET credentials_changed_at = '2000-01-01 00:00:00' WHERE id = ?", SELLER_A_USER_ID);
        expectOk(get(sellerUrl).with(authHeaders.seller(SELLER_A_USER_ID)));
        // 구매자 소유자(요청자)도 같은 검증을 탄다 — user 행을 만들어 탈퇴 처리
        jdbc.update("INSERT INTO `user` (id, public_id, withdrawn_at, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6), NOW(6))",
                OWNER_ID, "usr_" + ("T82OWNER" + "00000000000000000000000000").substring(0, 26));
        mockMvc.perform(get(FILES_URL + LINKED_KEY).with(authHeaders.buyer(OWNER_ID))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("셀러 거부(셀러 별칭): 타 셀러 구성원 → 연결·미연결 404 / 소속 없는 SELLER JWT(클레임 요청자와 같은 user id라도) → 404")
    void seller_otherOrUnaffiliated_rejected() throws Exception {
        expectNotFound(get(SELLER_FILES_URL + LINKED_KEY).with(authHeaders.seller(SELLER_B_USER_ID)));
        expectNotFound(get(SELLER_FILES_URL + UNLINKED_KEY).with(authHeaders.seller(SELLER_B_USER_ID)));
        expectNotFound(get(SELLER_FILES_URL + LINKED_KEY).with(authHeaders.seller(OWNER_ID)));
        expectNotFound(get(SELLER_FILES_URL + UNLINKED_KEY).with(authHeaders.seller(OWNER_ID)));
    }

    @Test
    @DisplayName("옛 첨부 경로는 구매자 경로(D-235 PR3 K5): 익명 → 401 · 셀러·관리자 역할 쿠키만 → 401(구매자 쿠키로 판정·다른 역할 쿠키 미사용)")
    void legacyPath_buyerOnly_othersUnauthenticated() throws Exception {
        mockMvc.perform(get(FILES_URL + LINKED_KEY)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(FILES_URL + LINKED_KEY).cookie(sellerRoleCookie(SELLER_A_USER_ID))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(FILES_URL + LINKED_KEY).cookie(adminRoleCookie(ADMIN_ID))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("옛 인증 수단은 읽지 않는다(D-235 PR3 K1·K6): 요청자 토큰을 auth_token·admin_token 쿠키나 Bearer로 보내면 401 / 무효 Bearer + 유효 구매자 쿠키 → 200(헤더 무시)")
    void legacyCredentials_notRecognized() throws Exception {
        String ownerToken = tokenProvider.issue(OWNER_ID, ActorRole.BUYER);
        mockMvc.perform(get(FILES_URL + LINKED_KEY).cookie(new Cookie("auth_token", ownerToken))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(FILES_URL + LINKED_KEY).cookie(new Cookie("admin_token", tokenProvider.issue(ADMIN_ID, ActorRole.ADMIN))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(FILES_URL + LINKED_KEY).header("Authorization", "Bearer " + ownerToken)).andExpect(status().isUnauthorized());
        expectOk(get(FILES_URL + LINKED_KEY).header("Authorization", "Bearer not.a.jwt").cookie(buyerRoleCookie(OWNER_ID)));
    }

    @Test
    @DisplayName("_thumb 키: 원본 행 규칙 그대로 — 요청자 200·ADMIN 200·타인 404·익명 401 / 지원하지 않는 썸네일 확장자(.gif) → 404 / 원본 후보 2건(png·webp 동시 존재) → 404")
    void thumbnail_followsOriginalRow_exactlyOneCandidate() throws Exception {
        expectOk(get(FILES_URL + LINKED_THUMB_KEY).with(authHeaders.buyer(OWNER_ID)));
        expectOk(get(ADMIN_FILES_URL + LINKED_THUMB_KEY).with(authHeaders.admin(ADMIN_ID)));
        expectNotFound(get(FILES_URL + LINKED_THUMB_KEY).with(authHeaders.buyer(OTHER_BUYER_ID)));
        mockMvc.perform(get(FILES_URL + LINKED_THUMB_KEY)).andExpect(status().isUnauthorized());

        writeStoredFile(LINKED_THUMB_UNSUPPORTED_KEY); // 파일이 있어도 인가 단계에서 거부
        expectNotFound(get(FILES_URL + LINKED_THUMB_UNSUPPORTED_KEY).with(authHeaders.buyer(OWNER_ID)));

        // 같은 base의 webp 원본 행이 추가되면 _thumb.png 후보가 2건 → 정확히 1건이 아니므로 요청자·ADMIN 모두 404(원본 png 자체는 영향 없음)
        seedAttachment(TWIN_ATTACHMENT_ID, UPLOADER_ID, CLAIM_ID, FILES_URL + LINKED_WEBP_TWIN_KEY);
        expectNotFound(get(FILES_URL + LINKED_THUMB_KEY).with(authHeaders.buyer(OWNER_ID)));
        expectNotFound(get(ADMIN_FILES_URL + LINKED_THUMB_KEY).with(authHeaders.admin(ADMIN_ID)));
        expectOk(get(FILES_URL + LINKED_KEY).with(authHeaders.buyer(OWNER_ID)));
    }

    @Test
    @DisplayName("인코딩 경로 GET /api/v1/files/%63laims/... + 요청자 구매자 쿠키 → 정상 경로와 동일 200 / 익명 → 401 (쿠키 선택·hasRole(BUYER)·컨트롤러 분기가 디코딩 경로로 일치)")
    void encodedPath_sameAsPlainPath() throws Exception {
        // 문자열 템플릿은 MockMvc가 %를 다시 인코딩(%2563)해 firewall 400이 되므로 URI 객체로 raw 경로를 그대로 보낸다.
        expectOk(get(URI.create(FILES_URL + LINKED_KEY_ENCODED)).with(authHeaders.buyer(OWNER_ID)));
        mockMvc.perform(get(URI.create(FILES_URL + LINKED_KEY_ENCODED))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/files/claims/... + 구매자 역할 쿠키만(CSRF 토큰 없음) → 403 FORBIDDEN (D-235: 쿠키 인증 unsafe 요청은 CSRF 필수)")
    void postOnClaimServingPath_cookieWithoutCsrf_forbidden() throws Exception {
        mockMvc.perform(post(FILES_URL + LINKED_KEY).cookie(buyerRoleCookie(OWNER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("미존재: 행 없는 파일(고아) → 404 / 행은 있고 파일 없음 → 404 / 200·404 모두 Cache-Control no-store, private 단일 헤더")
    void notFound_andCacheHeaders() throws Exception {
        expectNotFound(get(ADMIN_FILES_URL + NO_ROW_KEY).with(authHeaders.admin(ADMIN_ID)));
        Files.delete(uploadRoot.resolve(UNLINKED_KEY));
        expectNotFound(get(FILES_URL + UNLINKED_KEY).with(authHeaders.buyer(OWNER_ID)));

        assertThat(mockMvc.perform(get(FILES_URL + LINKED_KEY).with(authHeaders.buyer(OWNER_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeaders("Cache-Control"))
                .containsExactly(NO_STORE_PRIVATE);
        assertThat(mockMvc.perform(get(FILES_URL + LINKED_KEY).with(authHeaders.buyer(OTHER_BUYER_ID)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getHeaders("Cache-Control"))
                .containsExactly(NO_STORE_PRIVATE);
    }

    @Test
    @DisplayName("쿠키 인증 CSRF 경계: 구매자 역할 쿠키만(CSRF 토큰 없음)으로 POST /api/v1/claims/attachments → 403 FORBIDDEN(D-235) / products 익명 → 200 public immutable")
    void cookieOnlyUnsafeOutsideClaimServing_requiresCsrf() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "photo.png", "image/png", png(10, 10));
        mockMvc.perform(multipart("/api/v1/claims/attachments").file(file).cookie(buyerRoleCookie(OWNER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get(FILES_URL + PRODUCT_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
    }

    @Test
    @DisplayName("관리자 별칭 GET /api/v1/admin/files/claims/**: 관리자 역할 쿠키 → 연결 200·no-store / 미연결 404 / 구매자 쿠키만 → 401 / 옛 admin_token → 401")
    void adminAlias_adminRoleCookie() throws Exception {
        String url = ADMIN_FILES_URL + LINKED_KEY;
        mockMvc.perform(get(url).cookie(adminRoleCookie(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", NO_STORE_PRIVATE));
        expectOk(get(ADMIN_FILES_URL + LINKED_THUMB_KEY).cookie(adminRoleCookie(ADMIN_ID)));
        expectNotFound(get(ADMIN_FILES_URL + UNLINKED_KEY).cookie(adminRoleCookie(ADMIN_ID)));
        mockMvc.perform(get(url).cookie(buyerRoleCookie(OWNER_ID))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(url).cookie(new Cookie("admin_token", tokenProvider.issue(ADMIN_ID, ActorRole.ADMIN))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("셀러 별칭 GET /api/v1/seller/files/claims/**: 소유 셀러 역할 쿠키 → 200 / 타 셀러 404 / 관리자·구매자 쿠키만 → 401")
    void sellerAlias_sellerRoleCookie() throws Exception {
        String url = SELLER_FILES_URL + LINKED_KEY;
        expectOk(get(url).cookie(sellerRoleCookie(SELLER_A_USER_ID)));
        expectNotFound(get(url).cookie(sellerRoleCookie(SELLER_B_USER_ID)));
        mockMvc.perform(get(url).cookie(adminRoleCookie(ADMIN_ID))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(url).with(authHeaders.buyer(OWNER_ID))).andExpect(status().isUnauthorized());
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

    private Cookie buyerRoleCookie(long actorId) {
        return new Cookie("__Secure-buyer_at", tokenProvider.issue(actorId, ActorRole.BUYER));
    }

    private Cookie sellerRoleCookie(long actorId) {
        return new Cookie("__Secure-seller_at", tokenProvider.issue(actorId, ActorRole.SELLER));
    }

    private Cookie adminRoleCookie(long actorId) {
        return new Cookie("__Secure-admin_at", tokenProvider.issue(actorId, ActorRole.ADMIN));
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

    /** 셀러 A(품목 소유)·셀러 B(타 셀러) + 구성원 + 품목 행. 품목은 claim.order_item_id와 같은 id로 seller_id=셀러 A. */
    private void seedSellers() {
        tx.executeWithoutResult(status -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                seedSellerWithOwner(SELLER_A_USER_ID, SELLER_A_ID, "T82USA", "T82SLA");
                seedSellerWithOwner(SELLER_B_USER_ID, SELLER_B_ID, "T82USB", "T82SLB");
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                                + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1, 1000, 1000, 'RETURN_REQUESTED', NOW(6), NOW(6), 'T82', 1000)",
                        ORDER_ITEM_ID, "oit_" + ("T82OIT" + ORDER_ITEM_ID + "00000000000000000000000000").substring(0, 26),
                        ORDER_ITEM_ID, ORDER_ITEM_ID, ORDER_ITEM_ID, SELLER_A_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSellerWithOwner(long userId, long sellerId, String userTag, String sellerTag) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                userId, "usr_" + (userTag + "00000000000000000000000000").substring(0, 26));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', 'ACTIVE', NOW(6), NOW(6))", sellerId,
                "slr_" + (sellerTag + "00000000000000000000000000").substring(0, 26), sellerTag);
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", userId, sellerId);
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
        tx.executeWithoutResult(status -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?)", SELLER_A_USER_ID, SELLER_B_USER_ID);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SELLER_A_ID, SELLER_B_ID);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?, ?)", SELLER_A_USER_ID, SELLER_B_USER_ID, OWNER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}
