package com.zslab.mall.auth.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 운영 관리자(ADMIN_OPERATOR) 공급 요청(Track 38·SUPER_ADMIN 주도). {@code userPublicId}는 ADMIN_OPERATOR 역할을 부여할
 * 대상 회원의 public_id(usr_)다 — 회원 목록·회수 API와 같은 외부 식별자로 통일해 화면에서 바로 호출한다(Track 89-E·D-186 §1-A·
 * 내부 userId(BIGINT)는 노출하지 않음). 신규 회원을 만들지 않고 기존 회원에 역할만 부여한다.
 *
 * <p>형식(@NotBlank)만 본 DTO가 검증하고, 대상 회원 존재·중복 부여 여부는
 * {@link com.zslab.mall.auth.service.AdminOperatorProvisioningService}가 검증한다.
 */
public record AdminOperatorProvisioningRequest(
        @NotBlank String userPublicId) {
}
