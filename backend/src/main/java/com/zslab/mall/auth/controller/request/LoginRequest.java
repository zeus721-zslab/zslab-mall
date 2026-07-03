package com.zslab.mall.auth.controller.request;

import com.zslab.mall.common.security.ActorRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 로그인 요청. email·password·role. (Track 33) */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password,
        @NotNull ActorRole role) {
}
