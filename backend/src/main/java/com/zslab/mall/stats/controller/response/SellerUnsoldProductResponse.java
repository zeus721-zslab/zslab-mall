package com.zslab.mall.stats.controller.response;

/** 미판매 상품 1행(Track 90-E-3·SALE 상태·기간 내 결제 품목 0). 주문이 없어 이름은 현행 product.name이며 productKey는 항상 있다. */
public record SellerUnsoldProductResponse(
        String productKey,
        String productName,
        long basePrice) {
}
