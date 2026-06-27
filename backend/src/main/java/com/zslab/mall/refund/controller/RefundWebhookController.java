package com.zslab.mall.refund.controller;

import com.zslab.mall.refund.controller.request.RefundCallbackRequest;
import com.zslab.mall.refund.service.RefundService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PG 환불 콜백 수신 Controller(Track 5·expected-spec §6.2). HTTP 책임만 가지며 도메인 처리는 {@link RefundService}에 위임한다.
 *
 * <p><b>경로</b>: {@code POST /api/webhooks/refunds}(Track 3 {@code /api/webhooks/payments} REST 복수형 컨벤션 일치·[I4] B 채택).
 *
 * <p><b>응답 코드</b>: 정상 전이·멱등 NO-OP(RFN-3·이미 종결 상태 재수신)는 200, 형식 검증 실패는 400(Bean Validation)이다.
 * RFN-3 멱등은 {@link RefundService#handleCallback}가 예외 없이 선검사·no-op 반환하므로 200으로 응답한다(Track 3 동일).
 * pg_refund_id 미매칭·PAY-1 위반 등은 전역 예외 핸들러가 404/422로 매핑한다(D-48 일원화).
 */
@RestController
@RequestMapping("/api/webhooks/refunds")
public class RefundWebhookController {

    private final RefundService refundService;

    public RefundWebhookController(RefundService refundService) {
        this.refundService = refundService;
    }

    /** PG 환불 콜백을 수신해 처리한다. 정상·멱등 NO-OP는 200. */
    @PostMapping
    public ResponseEntity<Void> handleCallback(@RequestBody @Valid RefundCallbackRequest request) {
        refundService.handleCallback(request.pgRefundId(), request.status(), request.failureReason());
        return ResponseEntity.ok().build();
    }
}
