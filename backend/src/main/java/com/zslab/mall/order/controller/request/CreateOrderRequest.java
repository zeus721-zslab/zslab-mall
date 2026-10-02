package com.zslab.mall.order.controller.request;

import com.zslab.mall.checkout.command.CheckoutCommand;
import com.zslab.mall.checkout.command.CheckoutItemCommand;
import com.zslab.mall.payment.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 주문 생성 요청(§4·D-43 단일 체크아웃). discount·shipping 필드 미노출(D-61). method는 첫 결제 시작 수단. 품목 수 상한은 주문 합계가
 * long·수수료식 범위 안에 들도록 두는 입력 상한이다({@code Product.MAX_PRICE} 참조).
 */
public record CreateOrderRequest(
        @NotEmpty @Size(max = MAX_ITEMS) @Valid List<OrderItemRequest> items,
        @NotNull @Valid ShippingAddressRequest shippingAddress,
        @NotNull PaymentMethod method) {

    /** 주문 1건의 품목 수 상한. */
    public static final int MAX_ITEMS = 100;

    /** 헤더 유래 buyerId·idempotencyKey와 결합해 Service Command로 변환한다(D-41). */
    public CheckoutCommand toCommand(Long buyerId, String idempotencyKey) {
        List<CheckoutItemCommand> itemCommands = items.stream()
                .map(item -> new CheckoutItemCommand(item.productId(), item.variantId(), item.quantity()))
                .toList();
        return new CheckoutCommand(buyerId, idempotencyKey, itemCommands, shippingAddress.toCommand(), method);
    }
}
