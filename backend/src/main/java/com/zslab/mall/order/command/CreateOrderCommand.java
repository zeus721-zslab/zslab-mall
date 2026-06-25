package com.zslab.mall.order.command;

import java.util.List;

/**
 * 주문 생성 입력(Service 계층 Command). OrderService.createOrder 단일 진입.
 *
 * @param items OrderItem 입력 목록(최소 1개·ORD-1은 Service에서 가드)
 */
public record CreateOrderCommand(
        Long buyerId,
        List<OrderItemCommand> items,
        ShippingAddressCommand shipping,
        Long discountAmount,
        Long shippingFee) {
}
