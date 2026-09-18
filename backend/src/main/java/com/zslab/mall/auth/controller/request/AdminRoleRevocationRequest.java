package com.zslab.mall.auth.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 권한 회수 요청(Track 89-E·D-186). 회수는 대상이 운영 업무를 못 하게 되는 조치라 사유를 필수로 받아 감사 after에 남긴다(부여는
 * 되돌릴 수 있어 사유 없음·비대칭 의도). DELETE + 본문은 {@code CartController.removeItems} 선례를 따른다.
 */
public record AdminRoleRevocationRequest(
        @NotBlank @Size(max = 200) String reason) {
}
