package com.zslab.mall.file.controller;

import com.zslab.mall.file.service.FileStorage;
import com.zslab.mall.file.service.ImageFormat;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 업로드 파일 공개 서빙(Track 77·GET permitAll). 파일명이 ULID로 불변이므로 장기 캐시(1년·immutable)를 붙인다 — 내용 교체는
 * 새 파일 업로드 + URL 교체로만 일어난다. 클레임 첨부({@code claims/})는 {@code Cache-Control: no-store, private}로 캐시를 금지한다
 * (D-174·인가 서빙은 보안 트랙 이월). {@code X-Content-Type-Options: nosniff}는 Spring Security 기본 헤더가 모든 응답에 붙인다. 미존재·루트 밖 경로는 404 FILE_NOT_FOUND(GlobalExceptionHandler). Content-Type은
 * 저장 시 고정한 확장자에서 결정하며 알 수 없는 확장자는 octet-stream이다.
 */
@RestController
public class FileServingController {

    private static final long CACHE_MAX_AGE_DAYS = 365;
    /** 클레임 첨부(반품 사진) 키 접두사(D-174): 개인 사진이라 공유 캐시·디스크 캐시를 금지한다. 상품 이미지는 공개 캐시 유지. */
    private static final String CLAIM_KEY_PREFIX = "claims/";

    private final FileStorage fileStorage;

    public FileServingController(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @GetMapping("/api/v1/files/{*key}")
    public ResponseEntity<Resource> serve(@PathVariable("key") String key) {
        String relativeKey = key.startsWith("/") ? key.substring(1) : key;
        Path file = fileStorage.resolveExisting(relativeKey);
        return ResponseEntity.ok()
                .contentType(contentTypeOf(relativeKey))
                .cacheControl(cacheControlOf(relativeKey))
                .body(new FileSystemResource(file));
    }

    private static CacheControl cacheControlOf(String relativeKey) {
        if (relativeKey.startsWith(CLAIM_KEY_PREFIX)) {
            return CacheControl.noStore().cachePrivate();
        }
        return CacheControl.maxAge(CACHE_MAX_AGE_DAYS, TimeUnit.DAYS).cachePublic().immutable();
    }

    private static MediaType contentTypeOf(String relativeKey) {
        int dot = relativeKey.lastIndexOf('.');
        if (dot < 0) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        return ImageFormat.fromExtension(relativeKey.substring(dot + 1))
                .map(format -> MediaType.parseMediaType(format.contentType()))
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
    }
}
