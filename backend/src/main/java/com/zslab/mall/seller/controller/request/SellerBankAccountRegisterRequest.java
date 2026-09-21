package com.zslab.mall.seller.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 셀러 본인 정산계좌 등록 요청(Track 90-D-3·D-199). 형식 규칙은 관리자 등록 {@link AdminSellerBankAccountRegisterRequest}와 동일하다
 * (bank_code 20·계좌번호 숫자·하이픈 6~30·account_holder 50) — 액터가 달라도 같은 컬럼에 들어가므로 규칙이 갈리지 않는다.
 * 관리자 DTO를 그대로 쓰지 않는 이유는 셀러 API 계약이 관리자 DTO 변경(예: 사유 추가)에 끌려가지 않게 하기 위함이다.
 */
public record SellerBankAccountRegisterRequest(
        @NotBlank @Size(max = 20) String bankCode,
        @NotBlank @Size(min = 6, max = 30) @Pattern(regexp = "^[0-9-]+$", message = "계좌번호는 숫자와 하이픈만 허용합니다.")
        String accountNumber,
        @NotBlank @Size(max = 50) String accountHolder) {
}
