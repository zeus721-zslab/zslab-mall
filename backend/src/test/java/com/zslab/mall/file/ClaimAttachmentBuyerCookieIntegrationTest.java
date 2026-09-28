package com.zslab.mall.file;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.TokenProvider;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 옛 클레임 첨부 경로 GET /api/v1/files/claims/** 의 구매자 역할 쿠키 후보(D-235 개정 2) 통합 테스트. 옛 후보(Bearer·admin_token·auth_token)
 * 규칙은 {@link ClaimAttachmentServingIntegrationTest}가 검증하고, 여기서는 FE가 옛 쿠키 없이 구매자 역할 쿠키만 보낼 때 같은 열람 규칙
 * (연결 첨부 = claim.requested_by)이 적용되는지만 본다. claim 시드는 FK 때문에 FOREIGN_KEY_CHECKS=0 TX에서 하고 try-finally로 =1 복원한다.
 */
@AutoConfigureMockMvc
class ClaimAttachmentBuyerCookieIntegrationTest extends AbstractIntegrationTest {

    private static final long OWNER_ID = 83101L;
    private static final long OTHER_BUYER_ID = 83102L;
    private static final long CLAIM_ID = 83100L;
    private static final long ORDER_ITEM_ID = 83100L;
    private static final long ATTACHMENT_ID = 83101L;
    private static final String KEY = "claims/2026/09/01T83LINKED0000000000000001.png";
    private static final String URL = "/api/v1/files/" + KEY;
    private static final String BUYER_COOKIE = "__Secure-buyer_at";
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
        seed();
        Path target = uploadRoot.resolve(KEY);
        Files.createDirectories(target.getParent());
        Files.write(target, png());
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("구매자 역할 쿠키만: 클레임 요청자 → 200 no-store / 타 구매자 → 404 FILE_NOT_FOUND")
    void buyerRoleCookieOnly_ownerAllowed_otherRejected() throws Exception {
        mockMvc.perform(get(URL).cookie(buyerCookie(OWNER_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", NO_STORE_PRIVATE));
        mockMvc.perform(get(URL).cookie(buyerCookie(OTHER_BUYER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
    }

    @Test
    @DisplayName("후보 독립 평가: 무효 Bearer·무효 auth_token 뒤에 요청자 구매자 역할 쿠키 → 200(맨 뒤 후보도 평가된다)")
    void buyerRoleCookie_evaluatedAfterInvalidCandidates() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + INVALID_TOKEN)
                        .cookie(new Cookie("auth_token", INVALID_TOKEN), buyerCookie(OWNER_ID)))
                .andExpect(status().isOk());
    }

    private Cookie buyerCookie(long actorId) {
        return new Cookie(BUYER_COOKIE, tokenProvider.issue(actorId, ActorRole.BUYER));
    }

    // ? positional 바인딩·SQL injection 없음
    private void seed() {
        tx.executeWithoutResult(status -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, reason_detail, status, "
                                + "previous_order_item_status, requested_by, requested_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'RETURN', 'PRODUCT_DEFECT', 'T83', 'REQUESTED', 'DELIVERED', ?, NOW(6), NOW(6), NOW(6))",
                        CLAIM_ID, "clm_" + ("T83CLM" + CLAIM_ID + "00000000000000000000000000").substring(0, 26), ORDER_ITEM_ID, OWNER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO attachment (id, public_id, target_type, target_id, file_name, file_path, mime_type, file_size, "
                        + "display_order, uploaded_by, created_at, updated_at) "
                        + "VALUES (?, ?, 'CLAIM', ?, 'seed.png', ?, 'image/png', 1, 0, ?, ?, ?)",
                ATTACHMENT_ID, "att_" + ("T83ATT" + ATTACHMENT_ID + "00000000000000000000000000").substring(0, 26), CLAIM_ID,
                "/api/v1/files/" + KEY, OWNER_ID, now, now);
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    // ? positional 바인딩·SQL injection 없음
    private void cleanup() {
        jdbc.update("DELETE FROM attachment WHERE id = ?", ATTACHMENT_ID);
        jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_ID);
    }
}
