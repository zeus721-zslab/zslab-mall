package com.zslab.mall.checkout.command;

/**
 * 체크아웃 품목 입력(Service 계층 Command·D-64·D-65). 식별자는 public_id(prd_/var_)이며 CheckoutService가
 * findByPublicId로 해소해 BIGINT id·단가·sellerId를 도출한다. 가격은 클라이언트가 제공하지 않는다(D-56 신뢰 차단).
 */
public record CheckoutItemCommand(
        String productPublicId,
        String variantPublicId,
        int quantity) {
}
