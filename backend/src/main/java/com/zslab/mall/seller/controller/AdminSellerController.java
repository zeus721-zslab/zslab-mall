package com.zslab.mall.seller.controller;

import com.zslab.mall.seller.controller.request.SellerProvisioningRequest;
import com.zslab.mall.seller.controller.response.SellerProvisioningResponse;
import com.zslab.mall.seller.service.SellerProvisioningService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 판매자 provisioning REST 컨트롤러(Track 37). 관리자 주도 판매자 입점 1 endpoint를 노출한다.
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드 절대경로를 부여한다({@link com.zslab.mall.inventory.controller.AdminInventoryController}
 * 선례·D-105 §2 Q2 옵션 A). 인가는 SecurityConfig의 {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}가 강제하며,
 * provisioning은 actor id를 소비하지 않으므로 {@code AdminActorResolver}를 호출하지 않는다(기존 Admin 컨트롤러의 레거시
 * resolve() 헤더-검증 stub과 상이·소비처 없는 호출 회피·기조 4).
 *
 * <p>HTTP 책임만 가진다: 요청 검증·Service 위임·HTTP 변환. 입점 로직·중복 소속 차단·role 배선은
 * {@link SellerProvisioningService} 책임이다.
 */
@RestController
public class AdminSellerController {

    private final SellerProvisioningService sellerProvisioningService;

    public AdminSellerController(SellerProvisioningService sellerProvisioningService) {
        this.sellerProvisioningService = sellerProvisioningService;
    }

    /**
     * 관리자 주도 판매자 입점(Track 37). 성공 201 + sellerPublicId. owner 미존재 404·중복 소속 409·status 제한 400·
     * 검증 실패 400({@link SellerProvisioningService}·GlobalExceptionHandler).
     */
    @PostMapping("/api/v1/admin/sellers")
    public ResponseEntity<SellerProvisioningResponse> provision(
            @RequestBody @Valid SellerProvisioningRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sellerProvisioningService.provision(request));
    }
}
