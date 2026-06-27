package com.zslab.mall.refund.controller.request;

import com.zslab.mall.refund.enums.RefundCallbackStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * PG 환불 webhook 콜백 HTTP 요청 DTO(expected-spec §6.2). Bean Validation으로 형식을 검증한다.
 *
 * <p>Track 3 {@code PaymentCallbackRequest} 패턴 미러(Q2·CR-02 보류 — 공통화는 webhook 3개 이상 도달 시 promote).
 * {@code failureReason}은 FAIL 콜백에서만 채워지므로 검증을 강제하지 않는다(형식만·도메인 처리는 Service).
 *
 * @param pgRefundId    PG 부여 환불 식별자(행 매칭 키·RFN-3 멱등 키)
 * @param status        콜백 결과(SUCCESS·FAIL). 잘못된 값은 역직렬화 단계에서 400
 * @param failureReason 실패 사유(FAIL 시·로깅 전용)
 */
public record RefundCallbackRequest(
        @NotBlank String pgRefundId,
        @NotNull RefundCallbackStatus status,
        String failureReason) {
}
