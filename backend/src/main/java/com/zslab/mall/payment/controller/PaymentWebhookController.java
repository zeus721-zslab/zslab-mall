package com.zslab.mall.payment.controller;

import com.zslab.mall.payment.exception.InvalidCallbackException;
import com.zslab.mall.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PG 결제 콜백 수신 Controller(D-27·D-34). HTTP 책임만 가지며 도메인 처리는 {@link PaymentService}에 위임한다.
 *
 * <p><b>응답 코드(D-34)</b>: 정상 전이·멱등 NO-OP는 200, REJECT(종결 상태 불법 전이·행 미발견·PAY-3a anomaly)는 422,
 * 형식 검증 실패는 400(Bean Validation 기본)이다.
 *
 * <p>예외→상태 매핑은 컨트롤러 로컬 {@code @ExceptionHandler}로 처리한다. 컨트롤러가 늘어나면 전역
 * {@code @RestControllerAdvice}로 일원화한다(CLAUDE.md·별도 chore).
 */
@Slf4j
@RestController
@RequestMapping("/api/payments")
public class PaymentWebhookController {

    private final PaymentService paymentService;

    public PaymentWebhookController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** PG 콜백을 수신해 처리한다. 정상·멱등 NO-OP는 200. */
    @PostMapping("/callbacks")
    public ResponseEntity<Void> handleCallback(@RequestBody @Valid PaymentCallbackRequest request) {
        paymentService.handleCallback(request.toCommand());
        return ResponseEntity.ok().build();
    }

    /** REJECT 콜백을 HTTP 422로 응답한다(D-34). */
    // 컨트롤러 단일 단계 — 다수 도입 시 @RestControllerAdvice 일원화 chore 분리
    @ExceptionHandler(InvalidCallbackException.class)
    public ResponseEntity<Void> handleInvalidCallback(InvalidCallbackException exception) {
        log.warn("[PaymentWebhook] 콜백 거부(422): {}", exception.getMessage());
        return ResponseEntity.unprocessableEntity().build();
    }
}
