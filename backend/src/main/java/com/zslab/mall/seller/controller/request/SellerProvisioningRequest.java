package com.zslab.mall.seller.controller.request;

import com.zslab.mall.seller.enums.SellerStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 판매자 입점 provisioning 요청(Track 37·관리자 주도). @Size 상한은 Seller 엔티티 컬럼 길이를 SoT로 반영한다.
 *
 * <p>status 형식(@NotNull·유효 enum)은 본 DTO가, 값 제한(PENDING·ACTIVE만)은 도메인 규칙이라
 * {@link com.zslab.mall.seller.service.SellerProvisioningService}가 검증한다. contactEmail은 nullable 컬럼이라
 * {@code @NotBlank} 없이 값이 있을 때만 형식(@Pattern)·길이를 검증한다(null 통과·SignupRequest 이메일 @Pattern 정합).
 */
public record SellerProvisioningRequest(
        @NotBlank @Size(max = 100) String companyName, // SoT: Seller.companyName @Column(length=100)
        @Size(max = 20) String businessNo, // nullable — SoT: Seller.businessNo @Column(length=20)
        @NotBlank @Size(max = 50) String ceoName, // SoT: Seller.ceoName @Column(length=50)
        // contactEmail nullable — @Pattern은 null을 통과시키므로 값이 있을 때만 형식 검증(SignupRequest 패턴 동일)
        @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 254) String contactEmail, // SoT: Seller.contactEmail @Column(length=254)
        @Size(max = 20) String contactPhone, // nullable — SoT: Seller.contactPhone @Column(length=20)
        @NotNull SellerStatus status,
        @NotNull Long ownerUserId) {
}
