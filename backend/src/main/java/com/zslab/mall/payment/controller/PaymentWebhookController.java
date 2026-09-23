package com.zslab.mall.payment.controller;

import com.zslab.mall.payment.controller.request.PaymentCallbackRequest;
import com.zslab.mall.payment.exception.InvalidCallbackException;
import com.zslab.mall.payment.service.PaymentService;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueType;
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
 * <p><b>응답 코드(D-34 → Track 104-2 D-216)</b>: 정상 전이·멱등 NO-OP·내부 규칙과 충돌한 통지(불일치로 기록·상태 불변)는 200, 형식 검증
 * 실패는 400(Bean Validation)이다. PG에서 일어난 사실은 거부하지 않는다(invariants P1) — 충돌은 불일치 기록으로 남고 PG 재전송을 부르지 않는다.
 * <b>매칭 결제 없음은 예외</b>로 기록은 커밋하되 기존 422를 돌려준다 — 결제 시작 트랜잭션 커밋 전에 도착한 통지일 수 있어 PG 재전송으로
 * 반영돼야 하고, 재전송이 매칭되면 서비스가 그 행을 자동 해소한다(D-216 결정 1). 기록 없이 실패하는 경우(다른 주문과의 동시 pgTid 충돌 409·
 * 재고 확정 실패 422 등)도 4xx다.
 */
@RestController
@RequestMapping("/api/webhooks/payments")
public class PaymentWebhookController {

    private final PaymentService paymentService;

    public PaymentWebhookController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * PG 콜백을 수신해 처리한다. 정상·멱등 NO-OP·충돌 기록(불일치)은 200 — 충돌 여부는 PG에 돌려줄 정보가 아니다. 매칭 결제 없음만
     * 서비스 트랜잭션이 기록을 커밋한 뒤 여기서 422를 던져 PG 재전송을 부른다.
     */
    @PostMapping
    public ResponseEntity<Void> handleCallback(@RequestBody @Valid PaymentCallbackRequest request) {
        paymentService.handleCallback(request.toCommand())
                .filter(issueType -> issueType == ReconciliationIssueType.PG_UNMATCHED_CALLBACK)
                .ifPresent(issueType -> {
                    throw new InvalidCallbackException("결제 행을 찾을 수 없습니다: attemptKey=" + request.paymentAttemptKey());
                });
        return ResponseEntity.ok().build();
    }
}
