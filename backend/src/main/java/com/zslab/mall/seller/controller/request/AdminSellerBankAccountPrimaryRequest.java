package com.zslab.mall.seller.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 관리자 주 정산계좌 전환 요청(Track 89-F·D-188). 지급처 변경이므로 사유 필수. */
public record AdminSellerBankAccountPrimaryRequest(@NotBlank @Size(max = 200) String reason) {
}
