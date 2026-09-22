package com.zslab.mall.product.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 셀러 판매 상태 전환 요청 DTO(Track 96-5·D-206·관리자 {@link AdminProductSaleStatusRequest} 동형). 형식 검증만 담당하며 전이 합법성·
 * 주체 가드는 {@code SellerProductSaleStatusService}가 판단한다. SALE·STOPPED 2값만 {@code @Pattern}으로 잠근다(4층위 (3)·위반 400).
 */
public record SellerProductSaleStatusRequest(
        @NotBlank @Pattern(regexp = "^(SALE|STOPPED)$", message = "status는 SALE 또는 STOPPED만 허용합니다.")
        String status) {
}
