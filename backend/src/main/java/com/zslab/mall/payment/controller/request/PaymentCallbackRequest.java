package com.zslab.mall.payment.controller.request;

import com.zslab.mall.payment.command.PaymentCallbackCommand;
import com.zslab.mall.payment.enums.CallbackType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * PG 콜백 HTTP 요청 DTO(D-27 HTTP 책임). Bean Validation으로 형식을 검증하고 {@link PaymentCallbackCommand}로 변환한다.
 *
 * <p>{@code pgTid}·{@code metadata}는 실패·취소 콜백 등에서 비어 있을 수 있어 검증을 강제하지 않는다(형식만·도메인 검증은 Service).
 * 패키지는 D-41 정합으로 {@code controller/request/}에 위치한다.
 *
 * @param provider          PG사 식별자(pg_provider)
 * @param callbackType       콜백 타입(SUCCESS·FAILURE·CANCEL). 잘못된 값은 역직렬화 단계에서 400
 * @param paymentAttemptKey 결제 시도 식별자(pat_·행 매핑 1차 키·D-35)
 * @param pgTid              PG 거래 ID(미발급 케이스 null 허용)
 * @param occurredAt         콜백 발생 시각
 * @param metadata           PG 부가 정보(failureCode 등)
 */
public record PaymentCallbackRequest(
        @NotBlank String provider,
        @NotNull CallbackType callbackType,
        @NotBlank String paymentAttemptKey,
        String pgTid,
        @NotNull LocalDateTime occurredAt,
        Map<String, String> metadata) {

    /** HTTP 요청을 도메인 명령으로 변환한다(D-27 — Service에 HTTP 타입 유출 금지). */
    public PaymentCallbackCommand toCommand() {
        return new PaymentCallbackCommand(provider, callbackType, paymentAttemptKey, pgTid, occurredAt, metadata);
    }
}
