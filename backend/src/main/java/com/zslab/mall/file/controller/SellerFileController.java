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
 * Seller 액터용 상품 이미지 업로드 REST 컨트롤러(Track 90-C-1). multipart 필드명 {@code files}(다중). 저장 경로·검증·썸네일은
 * 관리자 업로드({@link AdminFileController})와 동일하게 {@link ImageUploadService#upload(List)}(products 디렉터리)를 그대로 쓴다 —
 * 셀러 이미지 등록은 서버 발급 상품 이미지 URL만 받으므로(D-174) 셀러도 이 경로로 URL을 발급받아야 한다.
 *
 * <p>인가는 SecurityConfig {@code /api/v1/seller/**}→{@code hasRole("SELLER")}가 강제하고, 셀러 상태 가드(D-190)는
 * {@link SellerActorResolver#resolve} 첫 줄 호출로 적용된다(POST이므로 SUSPENDED 셀러는 403 SELLER_SUSPENDED). 업로드 파일은
 * 셀러 소유 개념이 없어(상품에 붙일 때 소유권 검증) 해소된 sellerId는 사용하지 않는다.
 */
@RestController
public class SellerFileController {

    private final ImageUploadService imageUploadService;
    private final SellerActorResolver sellerActorResolver;

    public SellerFileController(ImageUploadService imageUploadService, SellerActorResolver sellerActorResolver) {
        this.imageUploadService = imageUploadService;
        this.sellerActorResolver = sellerActorResolver;
    }

    /** 이미지 업로드(jpg·png·webp·파일당 10MB·요청당 20장). 항상 200·파일별 결과(부분 실패 허용). 정지 셀러 403. */
    @PostMapping(value = "/api/v1/seller/files/images", consumes = "multipart/form-data")
    public ResponseEntity<ImageUploadResponse> uploadImages(
            @RequestPart("files") List<MultipartFile> files, HttpServletRequest httpRequest) {
        sellerActorResolver.resolve(httpRequest);
        return ResponseEntity.ok(imageUploadService.upload(files));
    }
}
