package com.zslab.mall.checkout.command;

import com.zslab.mall.order.command.ShippingAddressCommand;
import com.zslab.mall.payment.enums.PaymentMethod;
import java.util.List;

/**
 * 장바구니 결제(selected 품목 → 신규 주문 + 첫 결제 시작) 입력(Track 41 β·A-1). CheckoutService.checkout 진입.
 * 공통 필드(buyerId·idempotencyKey·shipping·method)는 {@link CheckoutCommand}와 동일하며 {@link CheckoutContext}로
 * 노출한다. items만 내부 id 기반({@link CartCheckoutItemCommand})으로 기존 public_id 계약과 분리한다(A-1).
 *
 * @param buyerId        인증 컨텍스트에서 해소된 buyer 식별자
 * @param idempotencyKey Idempotency-Key 헤더(null 허용·미전달 시 멱등성 미적용·§8)
 * @param items          장바구니 selected 품목(최소 1개·내부 variantId 기반)
 * @param shipping       배송지(요청 본문 유래·Cart 미보유)
 * @param method         첫 결제 수단(요청 본문 유래·Cart 미보유)
 */
public record CartCheckoutCommand(
        Long buyerId,
        String idempotencyKey,
        List<CartCheckoutItemCommand> items,
        ShippingAddressCommand shipping,
        PaymentMethod method) implements CheckoutContext {
}
