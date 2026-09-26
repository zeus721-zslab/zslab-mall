package com.zslab.mall.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.file.service.ImageDecodeLimiter;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 이미지 디코딩 메모리 한도 통합 테스트(D-230·관리자 업로드 경로). 예산 초과는 디코딩 차례를 잡지 않고(디코딩 호출 0) 거부되고, 동시 디코딩
 * 차례가 모두 점유되면 대기 한도 뒤 요청 전체가 503 UPLOAD_BUSY이며, 디코딩 실패 같은 예외 경로에서도 차례가 반납되는지 확인한다.
 * 대기 한도는 테스트 시간을 줄이려 짧게 둔다(동시 수는 기본값 1 유지).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "upload.decode-wait-timeout=300ms")
class ImageDecodeLimitIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_UPLOAD_URL = "/api/v1/admin/files/images";
    private static final long ADMIN_ID = 78200L;
    /** application.yml 기본값(D-230 재결정 · 동시 디코딩 1개). */
    private static final int MAX_CONCURRENT_DECODES = 1;
    /** application.yml 기본값(D-230 · 파일 처리 입장 5 = 처리 1 + 대기 4). */
    private static final int MAX_INFLIGHT_FILES = 5;
    private static final int SIXTEEN_BIT = 16;
    private static final int EIGHT_BIT = 8;
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
    @MockitoSpyBean
    private ImageDecodeLimiter imageDecodeLimiter;

    private int heldPermits;
    private int heldAdmissions;

    @AfterEach
    void tearDown() {
        for (; heldPermits > 0; heldPermits--) {
            imageDecodeLimiter.release();
        }
        for (; heldAdmissions > 0; heldAdmissions--) {
            imageDecodeLimiter.releaseFile();
        }
    }

    @Test
    @DisplayName("예산 초과(16비트 RGBA 4000x4000) → IMAGE_TOO_LARGE · 디코딩 차례 획득 0회(디코딩 전 거부)")
    void overBudget_neverAcquiresDecodePermit() throws Exception {
        mockMvc.perform(multipart(ADMIN_UPLOAD_URL)
                        .file(file("deep.png", UploadHardeningIntegrationTest.pngHeaderWithEmptyIdat(4_000, 4_000, SIXTEEN_BIT, COLOR_TYPE_RGBA)))
                        .headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].code").value("IMAGE_TOO_LARGE"));

        verify(imageDecodeLimiter, never()).acquire();
        verify(imageDecodeLimiter).admitFile();
        verify(imageDecodeLimiter).releaseFile();
        assertThat(imageDecodeLimiter.availableAdmissions()).isEqualTo(MAX_INFLIGHT_FILES);
    }

    @Test
    @DisplayName("입장권 5개 모두 점유 → 디코딩 차례를 기다리지 않고 즉시 503 UPLOAD_BUSY·저장 0 / 반납 후 200")
    void allAdmissionsHeld_returns503Immediately() throws Exception {
        for (int i = 0; i < MAX_INFLIGHT_FILES; i++) {
            imageDecodeLimiter.admitFile();
            heldAdmissions++;
        }
        long storedBefore = countStoredFiles(); // static @TempDir는 클래스 공유라 절대값 대신 요청 전후 차이로 본다

        mockMvc.perform(multipart(ADMIN_UPLOAD_URL).file(file("ok.png", png(10, 10))).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UPLOAD_BUSY"));
        verify(imageDecodeLimiter, never()).acquire();
        assertThat(countStoredFiles()).isEqualTo(storedBefore);

        tearDown();
        mockMvc.perform(multipart(ADMIN_UPLOAD_URL).file(file("ok.png", png(10, 10))).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));
        assertThat(imageDecodeLimiter.availableAdmissions()).isEqualTo(MAX_INFLIGHT_FILES);
    }

    @Test
    @DisplayName("동시 디코딩 차례(기본 1개) 모두 점유 → 대기 한도 뒤 503 UPLOAD_BUSY·저장 0 / 반납 후 같은 요청 200 성공")
    void allPermitsHeld_returns503_thenSucceedsAfterRelease() throws Exception {
        for (int i = 0; i < MAX_CONCURRENT_DECODES; i++) {
            imageDecodeLimiter.acquire();
            heldPermits++;
        }
        long storedBefore = countStoredFiles(); // static @TempDir는 클래스 공유라 절대값 대신 요청 전후 차이로 본다

        mockMvc.perform(multipart(ADMIN_UPLOAD_URL).file(file("ok.png", png(10, 10))).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UPLOAD_BUSY"))
                .andExpect(jsonPath("$.detail").value("이미지 처리 요청이 많습니다. 잠시 후 다시 시도해 주세요."));
        assertThat(countStoredFiles()).isEqualTo(storedBefore);
        assertThat(imageDecodeLimiter.availableAdmissions()).as("디코딩 대기 503이어도 입장권 반납").isEqualTo(MAX_INFLIGHT_FILES);

        tearDown();
        mockMvc.perform(multipart(ADMIN_UPLOAD_URL).file(file("ok.png", png(10, 10))).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));
        assertThat(imageDecodeLimiter.availablePermits()).isEqualTo(MAX_CONCURRENT_DECODES);
    }

    @Test
    @DisplayName("예외 경로 반납: 예산 이내·픽셀 없는 이미지(디코딩 실패 INVALID_IMAGE) 뒤에도 차례가 모두 반납됨")
    void decodeFailure_releasesPermit() throws Exception {
        mockMvc.perform(multipart(ADMIN_UPLOAD_URL)
                        .file(file("empty.png", UploadHardeningIntegrationTest.pngHeaderWithEmptyIdat(100, 100, EIGHT_BIT, COLOR_TYPE_RGBA)))
                        .headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].code").value("INVALID_IMAGE"));

        verify(imageDecodeLimiter).acquire();
        verify(imageDecodeLimiter).release();
        assertThat(imageDecodeLimiter.availablePermits()).isEqualTo(MAX_CONCURRENT_DECODES);
        assertThat(imageDecodeLimiter.availableAdmissions()).isEqualTo(MAX_INFLIGHT_FILES);
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("files", name, "image/png", content);
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static long countStoredFiles() throws IOException {
        if (!Files.exists(uploadRoot)) {
            return 0;
        }
        try (var stream = Files.walk(uploadRoot)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }
}
