package com.zslab.mall.seller.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 셀러 구성원 역할 변경 요청(Track 89-G). role은 SELLER_* 3값(DTO 층 잠금)·사유 필수(권한 변경·감사 UPDATE). */
public record AdminSellerMemberRoleChangeRequest(
        @NotBlank @Pattern(regexp = "^SELLER_(OWNER|MANAGER|STAFF)$") String role,
        @NotBlank @Size(max = 200) String reason) {
}
