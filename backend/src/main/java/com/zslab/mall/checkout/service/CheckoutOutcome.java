package com.zslab.mall.checkout.service;

import com.zslab.mall.order.controller.response.CheckoutResponse;

/**
 * CheckoutService 결과. 컨트롤러가 HTTP로 변환한다(thin controller·HTTP 타입 비의존).
 *
 * @param response 응답 본문
 * @param cached   true면 멱등성 캐시 재반환(HTTP 200·Location 없음·§10). false면 신규 처리(HTTP 201·location 사용).
 * @param location 201 응답의 Location 값(신규 주문 {@code /api/v1/orders/{ord_}}·재결제 {@code /api/v1/payments/{pay_}}·D-53). cached면 null.
 */
public record CheckoutOutcome(CheckoutResponse response, boolean cached, String location) {

    public static CheckoutOutcome created(CheckoutResponse response, String location) {
        return new CheckoutOutcome(response, false, location);
    }

    public static CheckoutOutcome cached(CheckoutResponse response) {
        return new CheckoutOutcome(response, true, null);
    }
}
