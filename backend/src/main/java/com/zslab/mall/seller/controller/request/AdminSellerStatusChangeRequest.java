package com.zslab.mall.seller.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 관리자 셀러 상태 전이 요청(Track 89-D·D-187). status는 목표 상태로 PENDING을 허용하지 않는다(입점 초기값 전용) — 4층위 enum 잠금의
 * DTO 층({@code AdminProductSaleStatusRequest} 선례·위반 400 VALIDATION_FAILED). 전이 합법성은 도메인({@code Seller.changeStatus})이
 * 판정한다(422). reason은 감사 이력·종료 아카이브(WithdrawnSeller.terminate_reason)에 남는다.
 */
public record AdminSellerStatusChangeRequest(
        @NotBlank @Pattern(regexp = "^(ACTIVE|SUSPENDED|TERMINATED)$") String status,
        @NotBlank @Size(max = 200) String reason) {
}
