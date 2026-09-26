package com.zslab.mall.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.zslab.mall.file.controller.response.ImageUploadResponse;
import com.zslab.mall.file.exception.UploadBusyException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 입장권·디코딩 차례 반납과 503 보상 단위 테스트(D-230). 저장소·제한기를 목으로 두고 (1) 디코딩 대기 초과·입장권 소진 503이면 같은 요청에서
 * 먼저 저장한 파일을 지우고 (2) 조기 실패·예외 경로에서도 입장권과 차례를 반납하며 예산 초과는 차례를 얻지 않고 (3) 실제 세마포어가 입장은
 * 즉시, 디코딩은 짧은 대기 뒤 503이고 반납 후 재획득되는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ImageUploadServiceDecodeLimitTest {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 25_000_000L;
    private static final long MAX_DECODE_BYTES = 104_857_600L;
    private static final String DIRECTORY = "products";
    /** application.yml 기본값(D-230 재결정 · 동시 디코딩 1개). */
    private static final int MAX_CONCURRENT_DECODES = 1;
    /** application.yml 기본값(D-230 · 파일 처리 입장 5 = 처리 1 + 대기 4). */
    private static final int MAX_INFLIGHT_FILES = 5;
    /** 10×10 RGB(300B)도 넘기게 만든 작은 예산 — 예산 초과 경로 재현용. */
    private static final long TINY_DECODE_BUDGET = 100L;

    @Mock
    private FileStorage fileStorage;
    @Mock
    private ImageDecodeLimiter imageDecodeLimiter;

    @Test
    @DisplayName("둘째 파일 디코딩 대기 초과(503) → 예외 전파 · 첫 파일 저장분 삭제(응답되지 않는 고아 방지) · 첫 파일 차례 반납")
    void busyOnSecondFile_deletesFirstStoredFile() throws Exception {
        doNothing().doThrow(new UploadBusyException("busy")).when(imageDecodeLimiter).acquire();
        ImageUploadService service = service();
        List<MultipartFile> files = List.of(pngFile("a.png"), pngFile("b.png"));

        assertThatThrownBy(() -> service.upload(files, DIRECTORY, new UploadLimits(2, MAX_FILE_SIZE)))
                .isInstanceOf(UploadBusyException.class);

        ArgumentCaptor<String> storedKey = ArgumentCaptor.forClass(String.class);
        verify(fileStorage).store(storedKey.capture(), any());
        verify(fileStorage).delete(storedKey.getValue());
        verify(imageDecodeLimiter, times(1)).release();
        verify(imageDecodeLimiter, times(2)).releaseFile(); // 디코딩 대기 503이어도 둘째 파일 입장권 반납
    }

    @Test
    @DisplayName("둘째 파일 차례 구간 예외(저장 실패 RuntimeException) → 예외 전파 · 두 차례 모두 반납 · 첫 파일 저장분 삭제")
    void exceptionInsidePermit_releasesAndDeletesEarlierFile() throws Exception {
        doNothing().doThrow(new IllegalStateException("disk full")).when(fileStorage).store(anyString(), any());
        ImageUploadService service = service();
        List<MultipartFile> files = List.of(pngFile("a.png"), pngFile("b.png"));

        assertThatThrownBy(() -> service.upload(files, DIRECTORY, new UploadLimits(2, MAX_FILE_SIZE)))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<String> storedKey = ArgumentCaptor.forClass(String.class);
        verify(fileStorage, times(2)).store(storedKey.capture(), any());
        verify(fileStorage).delete(storedKey.getAllValues().get(0));
        verify(imageDecodeLimiter, times(2)).acquire();
        verify(imageDecodeLimiter, times(2)).release();
        verify(imageDecodeLimiter, times(2)).admitFile();
        verify(imageDecodeLimiter, times(2)).releaseFile();
    }

    @Test
    @DisplayName("D-230 입장 한도: 둘째 파일 입장권 소진(503) → 예외 전파 · 첫 파일 저장분 삭제 · 입장권은 획득한 첫 파일만 반납 · 둘째 파일은 디코딩 차례 미획득")
    void admissionExhaustedOnSecondFile_deletesFirstStoredFile() throws Exception {
        doNothing().doThrow(new UploadBusyException("busy")).when(imageDecodeLimiter).admitFile();
        ImageUploadService service = service();
        List<MultipartFile> files = List.of(pngFile("a.png"), pngFile("b.png"));

        assertThatThrownBy(() -> service.upload(files, DIRECTORY, new UploadLimits(2, MAX_FILE_SIZE)))
                .isInstanceOf(UploadBusyException.class);

        ArgumentCaptor<String> storedKey = ArgumentCaptor.forClass(String.class);
        verify(fileStorage).store(storedKey.capture(), any());
        verify(fileStorage).delete(storedKey.getValue());
        verify(imageDecodeLimiter, times(1)).releaseFile();
        verify(imageDecodeLimiter, times(1)).acquire();
    }

    @Test
    @DisplayName("D-230 입장권 반납: 파일별 조기 실패(형식 불일치·예산 초과) 모두 입장권 반납 · 예산 초과는 디코딩 차례 획득 0회")
    void earlyFailures_releaseAdmission_withoutDecodePermit() throws Exception {
        ImageUploadService service = new ImageUploadService(fileStorage, MAX_FILE_SIZE, MAX_IMAGE_PIXELS, TINY_DECODE_BUDGET, imageDecodeLimiter);
        MultipartFile text = new MockMultipartFile("files", "fake.png", "image/png", "not an image".getBytes(StandardCharsets.UTF_8));

        ImageUploadResponse response = service.upload(List.of(text, pngFile("over-budget.png")), DIRECTORY, new UploadLimits(2, MAX_FILE_SIZE));

        assertThat(response.results()).extracting(ImageUploadResponse.Item::code).containsExactly("UNSUPPORTED_FORMAT", "IMAGE_TOO_LARGE");
        verify(imageDecodeLimiter, times(2)).admitFile();
        verify(imageDecodeLimiter, times(2)).releaseFile();
        verify(imageDecodeLimiter, never()).acquire();
    }

    @Test
    @DisplayName("실제 입장권(기본 5개): 모두 점유되면 대기 없이 즉시 UploadBusyException · 반납하면 다시 입장")
    void realLimiter_admissionImmediateRejection() {
        ImageDecodeLimiter limiter = new ImageDecodeLimiter(MAX_INFLIGHT_FILES, MAX_CONCURRENT_DECODES, Duration.ofSeconds(30));
        for (int i = 0; i < MAX_INFLIGHT_FILES; i++) {
            limiter.admitFile();
        }

        long started = System.nanoTime();
        assertThatThrownBy(limiter::admitFile).isInstanceOf(UploadBusyException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("대기 없이 즉시 판정(디코딩 대기 30초와 무관)").isLessThan(Duration.ofSeconds(1));

        limiter.releaseFile();
        limiter.admitFile();
        assertThat(limiter.availableAdmissions()).isZero();
        for (int i = 0; i < MAX_INFLIGHT_FILES; i++) {
            limiter.releaseFile();
        }
        assertThat(limiter.availableAdmissions()).isEqualTo(MAX_INFLIGHT_FILES);
    }

    @Test
    @DisplayName("실제 공정 세마포어(기본 1개): 점유 시 짧은 대기 뒤 UploadBusyException · 반납하면 다시 획득 · 인터럽트면 플래그 복원 후 503")
    void realLimiter_timeoutAndInterrupt() {
        ImageDecodeLimiter limiter = new ImageDecodeLimiter(MAX_INFLIGHT_FILES, MAX_CONCURRENT_DECODES, Duration.ofMillis(100));
        limiter.acquire();

        assertThatThrownBy(limiter::acquire).isInstanceOf(UploadBusyException.class);
        limiter.release();
        limiter.acquire();
        assertThat(limiter.availablePermits()).isZero();

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(limiter::acquire).isInstanceOf(UploadBusyException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // 테스트 스레드 인터럽트 상태 정리
        }
        limiter.release();
        assertThat(limiter.availablePermits()).isEqualTo(MAX_CONCURRENT_DECODES);
    }

    private ImageUploadService service() {
        return new ImageUploadService(fileStorage, MAX_FILE_SIZE, MAX_IMAGE_PIXELS, MAX_DECODE_BYTES, imageDecodeLimiter);
    }

    private static MultipartFile pngFile(String name) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", output);
        return new MockMultipartFile("files", name, "image/png", output.toByteArray());
    }
}
