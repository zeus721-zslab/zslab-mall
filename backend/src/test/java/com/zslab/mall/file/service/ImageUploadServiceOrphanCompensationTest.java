package com.zslab.mall.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 썸네일 실패 시 원본 고아 보상 단위 테스트(D-232). 실제 {@link FileStorage}를 임시 디렉터리에 두고 썸네일 저장만 실패시켜, 방금 저장한 원본이
 * 지워져 저장소에 파일이 남지 않는지와 원래 예외가 그대로 전파되는지(보상 삭제가 실패해도) 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ImageUploadServiceOrphanCompensationTest {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 25_000_000L;
    private static final long MAX_DECODE_BYTES = 104_857_600L;
    private static final String DIRECTORY = "products";
    /** 썸네일 폭(400px)을 넘겨 썸네일 저장 단계까지 가는 이미지 폭. */
    private static final int WIDE_IMAGE_WIDTH = 500;
    private static final int IMAGE_HEIGHT = 10;

    @TempDir
    private Path uploadRoot;
    @Mock
    private ImageDecodeLimiter imageDecodeLimiter;

    @Test
    @DisplayName("D-232: 원본 저장 뒤 썸네일 쓰기 도중 실패(파일 생성 후 예외) → 원본·부분 썸네일 삭제 · 저장소 파일 0 · 원래 예외 그대로 전파")
    void thumbnailStoreFailure_deletesOriginalAndPartialThumbnail() throws Exception {
        FileStorage storage = spy(new FileStorage(uploadRoot.toString()));
        IllegalStateException thumbnailFailure = new IllegalStateException("썸네일 저장 실패");
        doCallRealMethod().doAnswer(invocation -> {
            invocation.callRealMethod(); // CREATE_NEW 쓰기 도중 실패로 파일이 남은 상태를 재현
            throw thumbnailFailure;
        }).when(storage).store(anyString(), any());
        ImageUploadService service = service(storage);

        assertThatThrownBy(() -> service.upload(List.of(widePng()), DIRECTORY, new UploadLimits(1, MAX_FILE_SIZE)))
                .isSameAs(thumbnailFailure);

        ArgumentCaptor<String> storedKeys = ArgumentCaptor.forClass(String.class);
        verify(storage, times(2)).store(storedKeys.capture(), any());
        verify(storage).delete(storedKeys.getAllValues().get(0));
        verify(storage).delete(storedKeys.getAllValues().get(1));
        assertThat(storedFiles()).isEmpty();
    }

    @Test
    @DisplayName("D-232: 보상 삭제가 실패해도(delete false·warn) 원래 썸네일 예외가 전파된다 — 원본 1개는 잔존")
    void deleteFailure_doesNotMaskOriginalException() throws Exception {
        FileStorage storage = spy(new FileStorage(uploadRoot.toString()));
        IllegalStateException thumbnailFailure = new IllegalStateException("썸네일 저장 실패");
        doCallRealMethod().doThrow(thumbnailFailure).when(storage).store(anyString(), any());
        doReturn(false).when(storage).delete(anyString());
        ImageUploadService service = service(storage);

        assertThatThrownBy(() -> service.upload(List.of(widePng()), DIRECTORY, new UploadLimits(1, MAX_FILE_SIZE)))
                .isSameAs(thumbnailFailure);

        assertThat(storedFiles()).hasSize(1);
    }

    private ImageUploadService service(FileStorage storage) {
        return new ImageUploadService(storage, MAX_FILE_SIZE, MAX_IMAGE_PIXELS, MAX_DECODE_BYTES, imageDecodeLimiter);
    }

    private List<Path> storedFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(uploadRoot)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    private static MultipartFile widePng() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(WIDE_IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB), "png", output);
        return new MockMultipartFile("files", "wide.png", "image/png", output.toByteArray());
    }
}
