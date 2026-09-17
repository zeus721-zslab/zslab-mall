package com.zslab.mall.file.service;

import com.zslab.mall.file.exception.StoredFileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 로컬 디스크 파일 저장소(Track 77·D-166 D8 α). 루트는 {@code upload.path}(UPLOAD_PATH env·compose mall_uploads 볼륨)이며 저장·조회
 * 모두 "루트 기준 상대 키"(예 {@code products/2026/09/01M2....jpg})로 다룬다. 원본 파일명은 어떤 경로에도 쓰지 않는다.
 *
 * <p><b>traversal 차단</b>: 상대 키를 루트에 resolve한 뒤 {@link Path#normalize()} 결과가 루트 하위인지 검사한다. {@code ..}·절대경로·
 * 드라이브 문자 등 루트를 벗어나는 키는 존재하지 않는 파일과 동일하게 {@link StoredFileNotFoundException}(404)으로 은닉한다.
 */
@Slf4j
@Component
public class FileStorage {

    private final Path root;

    public FileStorage(@Value("${upload.path}") String uploadPath) {
        this.root = Paths.get(uploadPath).toAbsolutePath().normalize();
    }

    /** 상대 키 위치에 바이트를 저장한다(상위 디렉터리 자동 생성·동일 키 덮어쓰기 없음·CREATE_NEW). */
    public void store(String relativeKey, byte[] content) {
        Path target = resolveOrThrow(relativeKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new UncheckedIOException("파일 저장 실패: " + relativeKey, exception);
        }
    }

    /**
     * 상대 키 파일을 삭제한다(D-174 미연결 첨부 정리). 미존재는 false, 루트 밖 키·IO 실패는 warn 로그 후 false(예외 없음·재시도 없음).
     */
    public boolean delete(String relativeKey) {
        try {
            return Files.deleteIfExists(resolveOrThrow(relativeKey));
        } catch (StoredFileNotFoundException exception) {
            return false;
        } catch (IOException exception) {
            log.warn("[FileStorage] 파일 삭제 실패(고아 파일로 잔존): key={} — {}", relativeKey, exception.getMessage());
            return false;
        }
    }

    /** 상대 키 파일 존재 여부(루트 밖 키는 false·예외 없음). */
    public boolean exists(String relativeKey) {
        try {
            return Files.isRegularFile(resolveOrThrow(relativeKey));
        } catch (StoredFileNotFoundException exception) {
            return false;
        }
    }

    /**
     * 상대 키의 실제 파일 경로를 반환한다(서빙용).
     *
     * @throws StoredFileNotFoundException 루트 밖 키·미존재·디렉터리(404)
     */
    public Path resolveExisting(String relativeKey) {
        Path target = resolveOrThrow(relativeKey);
        if (!Files.isRegularFile(target)) {
            throw new StoredFileNotFoundException("파일을 찾을 수 없습니다: " + relativeKey);
        }
        return target;
    }

    private Path resolveOrThrow(String relativeKey) {
        if (relativeKey == null || relativeKey.isBlank()) {
            throw new StoredFileNotFoundException("파일 키가 비어 있습니다.");
        }
        Path resolved = root.resolve(relativeKey).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            log.warn("[FileStorage] 루트 밖 경로 접근 차단: key={}", relativeKey);
            throw new StoredFileNotFoundException("파일을 찾을 수 없습니다: " + relativeKey);
        }
        return resolved;
    }
}
