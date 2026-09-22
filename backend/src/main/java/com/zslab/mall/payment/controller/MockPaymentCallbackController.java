package com.zslab.mall.payment.controller;

import com.zslab.mall.common.auth.BuyerActorResolver;
import com.zslab.mall.payment.controller.request.MockPaymentCallbackRequest;
import com.zslab.mall.payment.service.MockPaymentCallbackService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * mock 결제 콜백 REST 컨트롤러(Track 93·D-198). 구매자(BUYER Bearer)가 자기 주문의 결제 시도에 모의 PG 결과를 통지하는 1 endpoint.
 * HTTP 책임만 가지며(D-27) 소유자 검증·서버 생성 필드·콜백 처리는 {@link MockPaymentCallbackService}에 위임한다.
 *
 * <p><b>mock 전용</b>: 실 PG 콜백 수신 경로는 {@code PaymentWebhookController}({@code POST /api/webhooks/payments}·무인증·gateway 외부 차단)이며
 * 본 endpoint는 브라우저 mock 결제 페이지 전용이며 {@code zslab.payment.gateway=mock}(미지정 시 mock)일 때만 등록된다(Track 97 D-209).
 * 실 모드에서는 404·SecurityConfig의 BUYER 매처는 무해하게 남는다(전환 가이드 제거 대상).
 *
 * <p><b>응답 코드</b>: 정상·멱등 NO-OP 200, 형식 400, 미인증 401, 비-BUYER 403(SecurityConfig), attemptKey 미존재·타인 주문 404,
 * 상태 조합 REJECT 422, pgTid 충돌 409.
 */
@RestController
@ConditionalOnProperty(name = "zslab.payment.gateway", havingValue = "mock", matchIfMissing = true)
public class MockPaymentCallbackController {

    private final MockPaymentCallbackService mockPaymentCallbackService;
    private final BuyerActorResolver buyerActorResolver;

    public MockPaymentCallbackController(
            MockPaymentCallbackService mockPaymentCallbackService, BuyerActorResolver buyerActorResolver) {
        this.mockPaymentCallbackService = mockPaymentCallbackService;
        this.buyerActorResolver = buyerActorResolver;
    }

    /** 구매자 본인 결제 시도에 mock 콜백을 적용한다. 정상·멱등 NO-OP는 200. */
    @PostMapping("/api/v1/payments/mock-callback")
    public ResponseEntity<Void> handleMockCallback(
            @RequestBody @Valid MockPaymentCallbackRequest request, HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        mockPaymentCallbackService.handleMockCallback(buyerId, request.attemptKey(), request.callbackType());
        return ResponseEntity.ok().build();
    }
}
