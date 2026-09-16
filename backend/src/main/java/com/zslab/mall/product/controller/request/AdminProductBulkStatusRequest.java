package com.zslab.mall.product.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 일괄 상태 변경 요청(Track 76). 허용 전이 = PENDING→SALE(승인)·SALE→STOPPED·STOPPED→SALE. SALE→PENDING 등은 불허(422·항목별 결과).
 * 항목 수 상한 100(단일 운영자 화면 페이지 크기 상한 정합).
 */
public record AdminProductBulkStatusRequest(
        @NotEmpty @Size(max = 100) List<@NotBlank String> productPublicIds,
        @NotBlank @Pattern(regexp = "^(SALE|STOPPED)$", message = "status는 SALE 또는 STOPPED만 허용합니다.")
        String status) {
}
