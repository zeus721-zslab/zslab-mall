package com.zslab.mall.payment.controller;

import com.zslab.mall.payment.controller.request.PaymentCallbackRequest;
import com.zslab.mall.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PG 결제 콜백 수신 Controller(D-27·D-34·§13). HTTP 책임만 가지며 도메인 처리는 {@link PaymentService}에 위임한다.
 *
 * <p><b>경로(§13·D-47)</b>: webhook은 버저닝 미적용 별도 경로 {@code /api/webhooks/payments}이다(구 {@code /api/payments/callbacks}에서 마이그레이션).
 *
 * <p><b>응답 코드(D-34)</b>: 정상 전이·멱등 NO-OP는 200, 형식 검증 실패는 400(Bean Validation)이다.
 * REJECT({@link com.zslab.mall.payment.exception.InvalidCallbackException})는 전역 예외 핸들러가 422로 응답한다(D-48 일원화·로컬 핸들러 제거).
 */
@RestController
@RequestMapping("/api/webhooks/payments")
public class PaymentWebhookController {

    private final PaymentService paymentService;

    public PaymentWebhookController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** PG 콜백을 수신해 처리한다. 정상·멱등 NO-OP는 200. */
    @PostMapping
    public ResponseEntity<Void> handleCallback(@RequestBody @Valid PaymentCallbackRequest request) {
        paymentService.handleCallback(request.toCommand());
        return ResponseEntity.ok().build();
    }
}
