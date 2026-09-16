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
 * 새 파일 업로드 + URL 교체로만 일어난다. 미존재·루트 밖 경로는 404 FILE_NOT_FOUND(GlobalExceptionHandler). Content-Type은
 * 저장 시 고정한 확장자에서 결정하며 알 수 없는 확장자는 octet-stream이다.
 */
@RestController
public class FileServingController {

    private static final long CACHE_MAX_AGE_DAYS = 365;

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
                .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE_DAYS, TimeUnit.DAYS).cachePublic().immutable())
                .body(new FileSystemResource(file));
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
