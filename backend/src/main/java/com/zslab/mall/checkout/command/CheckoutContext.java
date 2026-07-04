package com.zslab.mall.checkout.command;

import com.zslab.mall.order.command.ShippingAddressCommand;
import com.zslab.mall.payment.enums.PaymentMethod;

/**
 * 체크아웃 공통 컨텍스트(Track 41 seam(i)). {@link CheckoutCommand}(직접주문·public_id·α)와
 * {@link CartCheckoutCommand}(장바구니 결제·내부 id·β)가 공유하는 필드를 노출한다. CheckoutService의 멱등(D-52)·
 * 결제 initiate 오케스트레이션은 본 인터페이스만 참조하며, 품목 식별자 해소만 구현 타입별로 분기한다(B-1).
 *
 * <p>items는 타입이 상이하므로(public_id 2종 vs 내부 variantId) 본 인터페이스에 포함하지 않는다.
 */
public interface CheckoutContext {

    Long buyerId();

    String idempotencyKey();

    ShippingAddressCommand shipping();

    PaymentMethod method();
}
