package com.zslab.mall.checkout.command;

/**
 * 장바구니 결제 품목 입력(Track 41 β·내부 id 경로). CartItem이 보유한 내부 variantId(Long)를 그대로 전달하며,
 * CheckoutService가 {@code findByIdIn}으로 ProductVariant→Product를 해소해 단가·sellerId를 서버 산정한다(D-64 정합).
 * public_id 계약({@link CheckoutItemCommand})과 달리 productId·sellerId·가격은 클라이언트가 제공하지 않는다.
 */
public record CartCheckoutItemCommand(
        Long variantId,
        int quantity) {
}
