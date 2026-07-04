package com.zslab.mall.auth.controller.request;

import jakarta.validation.constraints.NotNull;

/**
 * 운영 관리자(ADMIN_OPERATOR) 공급 요청(Track 38·SUPER_ADMIN 주도). {@code userId}는 ADMIN_OPERATOR 역할을 부여할
 * 대상 회원의 식별자(BIGINT)다. 신규 회원을 만들지 않고 기존 회원에 역할만 부여한다.
 *
 * <p>형식(@NotNull)만 본 DTO가 검증하고, 대상 회원 존재·중복 부여 여부는
 * {@link com.zslab.mall.auth.service.AdminOperatorProvisioningService}가 검증한다.
 */
public record AdminOperatorProvisioningRequest(
        @NotNull Long userId) {
}
