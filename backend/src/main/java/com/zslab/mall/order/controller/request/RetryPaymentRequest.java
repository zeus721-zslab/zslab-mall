package com.zslab.mall.order.controller.request;

import com.zslab.mall.payment.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;

/**
 * 재결제 요청(§6 엔드포인트). 재시도 결제 수단을 받는다(원 결제와 다를 수 있어 별도 입력). 금액·주문 정보는 서버가 Order에서 재계산(D-61).
 */
public record RetryPaymentRequest(@NotNull PaymentMethod method) {
}
