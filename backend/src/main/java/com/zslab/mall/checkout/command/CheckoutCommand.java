package com.zslab.mall.checkout.command;

import com.zslab.mall.order.command.ShippingAddressCommand;
import com.zslab.mall.payment.enums.PaymentMethod;
import java.util.List;

/**
 * 체크아웃(신규 주문 + 첫 결제 시작) 입력(Service 계층 Command·D-58). CheckoutService.checkout 단일 진입.
 *
 * @param buyerId        X-Buyer-Id 유래(§2·D-39)
 * @param idempotencyKey Idempotency-Key 헤더(null 허용·미전달 시 멱등성 미적용·§8)
 * @param items          체크아웃 품목(최소 1개)
 * @param shipping       배송지
 * @param method         첫 결제 수단
 */
public record CheckoutCommand(
        Long buyerId,
        String idempotencyKey,
        List<CheckoutItemCommand> items,
        ShippingAddressCommand shipping,
        PaymentMethod method) {
}
