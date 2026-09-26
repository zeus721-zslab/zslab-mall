package com.zslab.mall.file.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.file.controller.response.ImageUploadResponse;
import com.zslab.mall.file.service.ImageUploadService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Seller 액터용 상품 이미지 업로드 REST 컨트롤러(Track 90-C-1·검토 반영). multipart 필드명 {@code files}(다중). 검증·썸네일은 관리자
 * 업로드({@link AdminFileController})와 같고, 저장 경로만 {@link ImageUploadService#uploadForSeller}가 셀러별 하위 디렉터리
 * ({@code products/sellers/{sellerId}/yyyy/MM/})로 분리해 파일을 발급 셀러에게 귀속시킨다 — 셀러 이미지 등록은 본인에게 발급된 URL만 받는다
 * ({@code ImageUploadService.requireSellerOwnedProductUrl}).
 *
 * <p>인가는 SecurityConfig {@code /api/v1/seller/**}→{@code hasRole("SELLER")}가 강제하고, 셀러 상태 가드(D-190)는
 * {@link SellerActorResolver#resolve} 첫 줄 호출로 적용된다(POST이므로 SUSPENDED 셀러는 403 SELLER_SUSPENDED).
 */
@RestController
public class SellerFileController {

    private final ImageUploadService imageUploadService;
    private final SellerActorResolver sellerActorResolver;

    public SellerFileController(ImageUploadService imageUploadService, SellerActorResolver sellerActorResolver) {
        this.imageUploadService = imageUploadService;
        this.sellerActorResolver = sellerActorResolver;
    }

    /** 이미지 업로드(jpg·png·파일당 10MB·요청당 20장·webp 업로드 중단 D-230). 항상 200·파일별 결과(부분 실패 허용). 정지 셀러 403. */
    @PostMapping(value = "/api/v1/seller/files/images", consumes = "multipart/form-data")
    public ResponseEntity<ImageUploadResponse> uploadImages(
            @RequestPart("files") List<MultipartFile> files, HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        return ResponseEntity.ok(imageUploadService.uploadForSeller(files, sellerId));
    }
}
