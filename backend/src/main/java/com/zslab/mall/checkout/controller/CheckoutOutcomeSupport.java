package com.zslab.mall.checkout.controller;

import com.zslab.mall.checkout.service.CheckoutOutcome;
import com.zslab.mall.order.controller.response.CheckoutResponse;
import java.net.URI;
import org.springframework.http.ResponseEntity;

/**
 * {@link CheckoutOutcome}를 HTTP 응답으로 변환하는 공통 지점(Track 41 D4). 직접주문(BuyerOrderController)·장바구니 결제
 * (CartCheckoutController)가 공유한다. 멱등성 캐시 재반환은 200(Location 없음·§10), 그 외 신규/재결제는 201 + Location(D-53).
 *
 * <p>{@link CheckoutOutcome}는 HTTP 타입 비의존을 유지하므로(record javadoc·thin controller), 변환 책임은 컨트롤러 계층
 * 헬퍼인 본 클래스가 가진다.
 */
public final class CheckoutOutcomeSupport {

    private CheckoutOutcomeSupport() {
    }

    /** 멱등 캐시 재반환은 200(Location 없음), 그 외 신규/재결제는 201 + Location(D-53). */
    public static ResponseEntity<CheckoutResponse> toResponseEntity(CheckoutOutcome outcome) {
        if (outcome.cached()) {
            return ResponseEntity.ok(outcome.response());
        }
        return ResponseEntity.created(URI.create(outcome.location())).body(outcome.response());
    }
}
