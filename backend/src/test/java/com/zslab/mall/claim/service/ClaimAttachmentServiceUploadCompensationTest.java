package com.zslab.mall.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.attachment.repository.AttachmentRepository;
import com.zslab.mall.file.service.FileStorage;
import com.zslab.mall.file.service.ImageDecodeLimiter;
import com.zslab.mall.file.service.ImageUploadService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 첨부 행 저장 실패 시 파일 고아 보상 단위 테스트(D-232). 실제 {@link ImageUploadService}·{@link FileStorage}로 임시 디렉터리에 파일을 저장하고
 * 행 조립·{@code saveAll}을 실패시켜, 이번 요청에서 저장한 원본·썸네일이 지워지는지와 원래 예외가 그대로 전파되는지(보상 삭제가 실패해도) 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ClaimAttachmentServiceUploadCompensationTest {

    private static final long BUYER_ID = 7001L;
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 25_000_000L;
    private static final long MAX_DECODE_BYTES = 104_857_600L;
    /** 썸네일 폭(400px)을 넘겨 원본·썸네일 2개를 저장하는 이미지 폭. */
    private static final int WIDE_IMAGE_WIDTH = 500;
    /** 썸네일 없이 원본만 저장하는 이미지 폭. */
    private static final int SMALL_IMAGE_WIDTH = 10;
    private static final int IMAGE_HEIGHT = 10;
    /** 넓은 이미지(원본+썸네일 2) + 작은 이미지(원본 1). */
    private static final int STORED_FILE_COUNT = 3;
    /** 성공 항목 2건 × deleteByUrl의 삭제 시도 2회(원본 + 썸네일 키). */
    private static final int SUCCESS_DELETE_CALLS = 4;

    @TempDir
    private Path uploadRoot;
    @Mock
    private ImageDecodeLimiter imageDecodeLimiter;
    @Mock
    private AttachmentRepository attachmentRepository;

    @Test
    @DisplayName("D-232: saveAll 실패 → 이번 요청 저장 파일(원본·썸네일) 삭제 · 저장소 파일 0 · 실패 항목은 저장·삭제 없음 · 원래 예외 그대로 전파")
    void saveAllFailure_deletesStoredFiles() throws Exception {
        FileStorage storage = spy(new FileStorage(uploadRoot.toString()));
        ClaimAttachmentService service = new ClaimAttachmentService(imageUploadService(storage), attachmentRepository);
        DataIntegrityViolationException saveFailure = new DataIntegrityViolationException("첨부 행 저장 실패");
        when(attachmentRepository.saveAll(any())).thenThrow(saveFailure);

        assertThatThrownBy(() -> service.upload(BUYER_ID, List.of(png("wide.png", WIDE_IMAGE_WIDTH),
                png("small.png", SMALL_IMAGE_WIDTH), notImage())))
                .isSameAs(saveFailure);

        verify(storage, times(STORED_FILE_COUNT)).store(anyString(), any());
        // 성공 2건 × deleteByUrl(원본 + 썸네일 키) = 4회 — 실패 항목(fake.png)은 삭제 대상이 아니다
        verify(storage, times(SUCCESS_DELETE_CALLS)).delete(anyString());
        assertThat(storedFiles()).isEmpty();
    }

    @Test
    @DisplayName("D-232: 파일명이 공백이라 행 조립(createUnlinked)이 실패(400) → saveAll 전이어도 저장 파일 삭제 · 저장소 파일 0 · 원래 예외 전파")
    void blankFileName_rowAssemblyFailure_deletesStoredFiles() throws Exception {
        FileStorage storage = spy(new FileStorage(uploadRoot.toString()));
        ClaimAttachmentService service = new ClaimAttachmentService(imageUploadService(storage), attachmentRepository);

        assertThatThrownBy(() -> service.upload(BUYER_ID, List.of(png(" ", WIDE_IMAGE_WIDTH))))
                .isInstanceOf(IllegalArgumentException.class);

        verify(storage, times(2)).store(anyString(), any());
        verify(attachmentRepository, never()).saveAll(any());
        assertThat(storedFiles()).isEmpty();
    }

    @Test
    @DisplayName("D-232: 보상 삭제가 실패해도(delete false·warn) 원래 saveAll 예외가 전파된다 — 저장 파일은 잔존")
    void deleteFailure_doesNotMaskOriginalException() throws Exception {
        FileStorage storage = spy(new FileStorage(uploadRoot.toString()));
        doReturn(false).when(storage).delete(anyString());
        ClaimAttachmentService service = new ClaimAttachmentService(imageUploadService(storage), attachmentRepository);
        DataIntegrityViolationException saveFailure = new DataIntegrityViolationException("첨부 행 저장 실패");
        when(attachmentRepository.saveAll(any())).thenThrow(saveFailure);

        assertThatThrownBy(() -> service.upload(BUYER_ID, List.of(png("wide.png", WIDE_IMAGE_WIDTH))))
                .isSameAs(saveFailure);

        assertThat(storedFiles()).hasSize(2);
    }

    private ImageUploadService imageUploadService(FileStorage storage) {
        return new ImageUploadService(storage, MAX_FILE_SIZE, MAX_IMAGE_PIXELS, MAX_DECODE_BYTES, imageDecodeLimiter);
    }

    private List<Path> storedFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(uploadRoot)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    private static MultipartFile png(String name, int width) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB), "png", output);
        return new MockMultipartFile("files", name, "image/png", output.toByteArray());
    }

    private static MultipartFile notImage() {
        return new MockMultipartFile("files", "fake.png", "image/png", "not an image".getBytes(StandardCharsets.UTF_8));
    }
}
