package com.zslab.mall.product.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 운영자 판매 상태 전환 요청 DTO(Track 71). 형식 검증만 담당하며 전이 합법성은 {@code ProductSaleStatusService}가 판단한다.
 *
 * <p>status는 ProductStatus 전체가 아니라 전환 대상 2값(SALE·STOPPED)만 허용하므로 enum 직접 바인딩 대신 {@code @Pattern}으로
 * 잠근다(CLAUDE.md 4층위 (3)·HIDDEN 등 다른 enum 값 유입 차단·위반 시 400).
 */
public record AdminProductSaleStatusRequest(
        @NotBlank @Pattern(regexp = "^(SALE|STOPPED)$", message = "status는 SALE 또는 STOPPED만 허용합니다.")
        String status) {
}
