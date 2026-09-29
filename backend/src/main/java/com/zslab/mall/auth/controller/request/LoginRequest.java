package com.zslab.mall.auth.controller.request;

import jakarta.validation.constraints.NotBlank;

/** 역할 로그인 요청(D-235 PR3 K3). 역할은 경로(구매자·/api/v1/seller·/api/v1/admin)가 정하므로 본문에 싣지 않는다. */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password) {
}
