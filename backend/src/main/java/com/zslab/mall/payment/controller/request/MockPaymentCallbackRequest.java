package com.zslab.mall.payment.controller.request;

import com.zslab.mall.payment.enums.CallbackType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * mock 결제 콜백 요청 DTO(Track 93·D-198). 구매자 브라우저(mock 결제 페이지)가 보내는 두 값만 받는다 — provider·pgTid·occurredAt은
 * 서버가 생성한다(FE 입력 금지·감사 필드 오염 차단).
 *
 * @param attemptKey   결제 시도 식별자(pat_·체크아웃 응답 redirectUrl로 발급)
 * @param callbackType 콜백 타입(SUCCESS·FAILURE·CANCEL). 잘못된 값은 역직렬화 단계에서 400
 */
public record MockPaymentCallbackRequest(
        @NotBlank String attemptKey,
        @NotNull CallbackType callbackType) {
}
