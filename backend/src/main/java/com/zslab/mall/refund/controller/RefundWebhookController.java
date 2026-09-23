package com.zslab.mall.refund.controller;

import com.zslab.mall.reconciliation.enums.ReconciliationIssueType;
import com.zslab.mall.refund.controller.request.RefundCallbackRequest;
import com.zslab.mall.refund.exception.RefundNotFoundException;
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
 * PAY-1 초과·FAILED 환불의 성공·전액 환불인데 결제 취소 불가는 불일치로 기록하고 상태를 바꾸지 않은 채 200이다(Track 104-2 D-216·
 * invariants P1 — 구 422/500). pg_refund_id 미매칭은 기록을 커밋한 뒤 기존 404를 유지한다 — 환불 개시 커밋 전에 도착한 통지일 수 있어
 * PG 재전송으로 반영돼야 하고, 재전송이 매칭되면 서비스가 그 행을 자동 해소한다(D-216 결정 1). 그 밖의 예외는 전역 예외 핸들러가 매핑한다(D-48 일원화).
 */
@RestController
@RequestMapping("/api/webhooks/refunds")
public class RefundWebhookController {

    private final RefundService refundService;

    public RefundWebhookController(RefundService refundService) {
        this.refundService = refundService;
    }

    /** PG 환불 콜백을 수신해 처리한다. 정상·멱등 NO-OP·충돌 기록(불일치)은 200, 매칭 환불 없음은 기록 커밋 뒤 404. */
    @PostMapping
    public ResponseEntity<Void> handleCallback(@RequestBody @Valid RefundCallbackRequest request) {
        refundService.handleCallback(request.pgRefundId(), request.status(), request.failureReason())
                .filter(issueType -> issueType == ReconciliationIssueType.PG_UNMATCHED_CALLBACK)
                .ifPresent(issueType -> {
                    throw new RefundNotFoundException("환불 행을 찾을 수 없습니다: pgRefundId=" + request.pgRefundId());
                });
        return ResponseEntity.ok().build();
    }
}
