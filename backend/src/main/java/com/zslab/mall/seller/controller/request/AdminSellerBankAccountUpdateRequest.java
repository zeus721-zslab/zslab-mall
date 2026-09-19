package com.zslab.mall.seller.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 관리자 셀러 정산계좌 수정 요청(Track 89-F·D-188·PUT 전체 필드). 계좌 정보 변경은 지급처 변경이므로 사유 필수(@NotBlank·400 VALIDATION_FAILED).
 * 형식 규칙은 등록 요청과 같다. 정산이 참조하는 행은 서비스가 409로 차단한다.
 */
public record AdminSellerBankAccountUpdateRequest(
        @NotBlank @Size(max = 20) String bankCode,
        @NotBlank @Size(min = 6, max = 30) @Pattern(regexp = "^[0-9-]+$", message = "계좌번호는 숫자와 하이픈만 허용합니다.")
        String accountNumber,
        @NotBlank @Size(max = 50) String accountHolder,
        @NotBlank @Size(max = 200) String reason) {
}
