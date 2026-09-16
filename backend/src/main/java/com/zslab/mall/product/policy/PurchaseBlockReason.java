package com.zslab.mall.product.policy;

/**
 * 구매 차단 사유(Track 76·{@link ProductPurchasePolicy} 판정 결과). 호출처가 자기 예외·응답 코드로 매핑한다
 * (주문: NOT_ON_SALE → PRODUCT_NOT_ON_SALE·SOLD_OUT → OUT_OF_STOCK / 장바구니·카탈로그: 둘 다 구매불가·품절).
 */
public enum PurchaseBlockReason {
    /** 상품·변형이 판매 상태가 아님(상태≠SALE·판매기간 밖·삭제·변형≠SALE·미존재). */
    NOT_ON_SALE,
    /** 판매 중이나 품절(상품·변형 수동 품절 또는 가용 재고 부족). */
    SOLD_OUT
}
