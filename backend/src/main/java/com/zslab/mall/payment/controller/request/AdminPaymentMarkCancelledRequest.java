package com.zslab.mall.payment.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 관리자 수동 결제 취소 요청(Track 89-A). 사유는 감사 로그(PAYMENT·after.reason)에만 남는다(Payment 컬럼 없음). */
public record AdminPaymentMarkCancelledRequest(
        @NotBlank @Size(max = 200) String reason) {
}
