package com.zslab.mall.seller.controller.request;

import com.zslab.mall.settlement.service.CommissionRateResolver;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 관리자 셀러 정보 수정 요청(Track 89-D·PUT 전체 필드). @Size 상한은 Seller 엔티티 컬럼 길이를 SoT로 반영한다(입점 요청 정합).
 * commissionRate는 basis-point(1000 = 10.00%)·null = 개별 계약 없음(카테고리율 → 플랫폼 기본율). 범위는 {@link CommissionRateResolver}
 * 상수(0~10000)와 같다. reason은 commissionRate가 실제로 바뀔 때만 필수이며 그 판정은 Service diff가 한다(89-C 카테고리 선례).
 */
public record AdminSellerUpdateRequest(
        @NotBlank @Size(max = 100) String companyName, // SoT: Seller.companyName @Column(length=100)
        @Size(max = 20) String businessNo, // nullable — SoT: Seller.businessNo @Column(length=20)
        @NotBlank @Size(max = 50) String ceoName, // SoT: Seller.ceoName @Column(length=50)
        // contactEmail nullable — @Pattern은 null을 통과시키므로 값이 있을 때만 형식 검증(입점 요청 동일)
        @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 254) String contactEmail, // SoT: Seller.contactEmail @Column(length=254)
        @Size(max = 20) String contactPhone, // nullable — SoT: Seller.contactPhone @Column(length=20)
        @Min(CommissionRateResolver.MIN_COMMISSION_RATE) @Max(CommissionRateResolver.MAX_COMMISSION_RATE) Integer commissionRate,
        @Size(max = 200) String reason) {
}
