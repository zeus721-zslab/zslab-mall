package com.zslab.mall.file.controller;

import com.zslab.mall.attachment.service.ClaimAttachmentAuthorizationService;
import com.zslab.mall.common.security.RequestTokenCandidates;
import com.zslab.mall.file.exception.StoredFileNotFoundException;
import com.zslab.mall.file.service.FileStorage;
import com.zslab.mall.file.service.ImageFormat;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 업로드 파일 서빙(Track 77·GET permitAll). 파일명이 ULID로 불변이므로 장기 캐시(1년·immutable)를 붙인다 — 내용 교체는
 * 새 파일 업로드 + URL 교체로만 일어난다. 클레임 첨부({@code claims/})는 {@code Cache-Control: no-store, private}로 캐시를 금지하고
 * (D-174) {@link ClaimAttachmentAuthorizationService}가 열람을 인가한다(Track 82 D-176·거부·미존재 모두 404로 존재 여부 비노출).
 * {@code X-Content-Type-Options: nosniff}는 Spring Security 기본 헤더가 모든 응답에 붙인다. 미존재·루트 밖 경로는 404 FILE_NOT_FOUND(GlobalExceptionHandler). Content-Type은
 * 저장 시 고정한 확장자에서 결정하며 알 수 없는 확장자는 octet-stream이다.
 */
@RestController
public class FileServingController {

    private static final long CACHE_MAX_AGE_DAYS = 365;
    /** 클레임 첨부(반품 사진) 키 접두사(D-174): 개인 사진이라 공유 캐시·디스크 캐시를 금지한다. 상품 이미지는 공개 캐시 유지. */
    private static final String CLAIM_KEY_PREFIX = "claims/";
    private static final CacheControl CLAIM_CACHE_CONTROL = CacheControl.noStore().cachePrivate();

    private final FileStorage fileStorage;
    private final ClaimAttachmentAuthorizationService claimAttachmentAuthorizationService;

    public FileServingController(FileStorage fileStorage,
            ClaimAttachmentAuthorizationService claimAttachmentAuthorizationService) {
        this.fileStorage = fileStorage;
        this.claimAttachmentAuthorizationService = claimAttachmentAuthorizationService;
    }

    @GetMapping("/api/v1/files/{*key}")
    public ResponseEntity<Resource> serve(@PathVariable("key") String key, HttpServletRequest request,
            HttpServletResponse response) {
        String relativeKey = key.startsWith("/") ? key.substring(1) : key;
        if (relativeKey.startsWith(CLAIM_KEY_PREFIX)) {
            return serveClaimAttachment(relativeKey, request, response);
        }
        Path file = fileStorage.resolveExisting(relativeKey);
        return ResponseEntity.ok()
                .contentType(contentTypeOf(relativeKey))
                .cacheControl(cacheControlOf(relativeKey))
                .body(new FileSystemResource(file));
    }

    /**
     * 클레임 첨부 인가 서빙(D-176). 캐시 금지 헤더를 서블릿 응답에 먼저 써서 거부·미존재 404(GlobalExceptionHandler 경로)에도 남긴다
     * (Spring Security 기본 Cache-Control은 이미 있는 헤더를 덮어쓰지 않는다). 200 본문은 이 헤더를 그대로 쓰므로 cacheControl을 다시 붙이지
     * 않는다(중복 헤더 방지).
     *
     * @throws StoredFileNotFoundException 열람 권한 없음·첨부 행 없음·파일 없음(모두 404)
     */
    private ResponseEntity<Resource> serveClaimAttachment(String relativeKey, HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CLAIM_CACHE_CONTROL.getHeaderValue());
        if (!claimAttachmentAuthorizationService.canView(relativeKey, RequestTokenCandidates.of(request))) {
            throw new StoredFileNotFoundException("파일을 찾을 수 없습니다: " + relativeKey);
        }
        Path file = fileStorage.resolveExisting(relativeKey);
        return ResponseEntity.ok()
                .contentType(contentTypeOf(relativeKey))
                .body(new FileSystemResource(file));
    }

    private static CacheControl cacheControlOf(String relativeKey) {
        if (relativeKey.startsWith(CLAIM_KEY_PREFIX)) {
            return CLAIM_CACHE_CONTROL;
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
