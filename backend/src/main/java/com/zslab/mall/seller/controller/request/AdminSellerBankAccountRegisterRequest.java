package com.zslab.mall.seller.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 관리자 셀러 정산계좌 등록 요청(Track 89-F·D-188). @Size 상한은 SellerBankAccount 컬럼 길이를 SoT로 반영한다(bank_code 20·account_holder 50).
 * accountNumber는 숫자·하이픈만 허용(국내 계좌번호 형식·공백 불가)·최대 30자 — 암호문 길이(약 80자)가 VARCHAR(255) 안에 들도록 상한을 둔다.
 * 최초 등록은 사유가 없다(감사 CREATE만·89-E "되돌릴 수 있는 부여는 사유 없음" 선례).
 */
public record AdminSellerBankAccountRegisterRequest(
        @NotBlank @Size(max = 20) String bankCode,
        @NotBlank @Size(min = 6, max = 30) @Pattern(regexp = "^[0-9-]+$", message = "계좌번호는 숫자와 하이픈만 허용합니다.")
        String accountNumber,
        @NotBlank @Size(max = 50) String accountHolder) {
}
