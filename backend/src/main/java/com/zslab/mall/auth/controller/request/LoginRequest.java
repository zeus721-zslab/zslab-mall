package com.zslab.mall.auth.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 역할 로그인 요청(D-235 PR3 K3). 역할은 경로(구매자·/api/v1/seller·/api/v1/admin)가 정하므로 본문에 싣지 않는다.
 *
 * @param publicDemo 공개 관리자 데모 로그인 대행(Nuxt _admin-demo)이 true로 보낸다. 관리자 로그인에서만 의미가 있고 없으면 false
 *                   (최종 점검 K1 — 발급 토큰에 데모 표식을 심어 계정·권한 변경과 시더를 막는다).
 */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password,
        Boolean publicDemo) {
}
