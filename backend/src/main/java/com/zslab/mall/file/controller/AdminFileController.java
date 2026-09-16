package com.zslab.mall.file.controller;

import com.zslab.mall.file.controller.response.ImageUploadResponse;
import com.zslab.mall.file.service.ImageUploadService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Admin 액터용 이미지 업로드 REST 컨트롤러(Track 77). multipart 필드명 {@code files}(다중). 인가는 SecurityConfig의
 * {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}가 강제한다. HTTP 책임만 가진다: 파트 바인딩·Service 위임·200 변환.
 * files 파트 누락 400(MissingServletRequestPart)·용량 초과 413(MaxUploadSizeExceeded)·장수 초과 400은 GlobalExceptionHandler.
 */
@RestController
public class AdminFileController {

    private final ImageUploadService imageUploadService;

    public AdminFileController(ImageUploadService imageUploadService) {
        this.imageUploadService = imageUploadService;
    }

    /** 이미지 업로드(jpg·png·webp·파일당 10MB·요청당 20장). 항상 200·파일별 결과(부분 실패 허용). */
    @PostMapping(value = "/api/v1/admin/files/images", consumes = "multipart/form-data")
    public ResponseEntity<ImageUploadResponse> uploadImages(@RequestPart("files") List<MultipartFile> files) {
        return ResponseEntity.ok(imageUploadService.upload(files));
    }
}
