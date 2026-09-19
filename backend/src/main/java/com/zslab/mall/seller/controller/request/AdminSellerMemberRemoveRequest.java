package com.zslab.mall.seller.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 셀러 구성원 제거 요청(Track 89-G). 셀러 접근이 즉시 끊기는 조치라 사유 필수(운영자 역할 회수 선례·D-186 §1-A 5). DELETE + body. */
public record AdminSellerMemberRemoveRequest(@NotBlank @Size(max = 200) String reason) {
}
