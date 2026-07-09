package com.zslab.mall.cart.controller.response;

/**
 * 장바구니 단일 품목 조회 뷰(Track 45). 담김 상태(variantPublicId·quantity·selected)에 상품 enrich(상품명·상호명·단가·가용재고·
 * 구매가능·이미지)를 더한다. cart_item 내부 PK는 노출하지 않는다(D-124 관례·대상키는 variantPublicId(var_·비정규화 스냅샷)).
 *
 * <p>dangling(담긴 후 variant soft-delete·비-SALE·판매자 비-ACTIVE)은 삭제하지 않고 {@code purchasable=false}로 표기한다.
 * soft-delete로 enrich가 누락된 경우 productName·sellerName·thumbnailUrl은 null, displayPrice·quantityAvailable은 0이다.
 * variantPublicId는 cart_item 저장 스냅샷이라 dangling이어도 항상 보유한다(외부 대상키·조회/삭제 가능).
 *
 * @param variantPublicId 상품 변형 외부 식별자(var_·대상키·cart_item 비정규화 스냅샷)
 * @param quantity 담은 수량
 * @param selected 결제 대상 선택 여부
 * @param productName 상품명(dangling 시 null)
 * @param sellerName 판매자 상호명(dangling 시 null)
 * @param displayPrice 단가 = basePrice + additionalPrice(dangling 시 0)
 * @param quantityAvailable 가용재고(dangling 시 0)
 * @param purchasable 구매가능 여부(품절 아님 ∧ variant SALE ∧ product SALE ∧ seller ACTIVE ∧ enrich 존재)
 * @param thumbnailUrl 대표 이미지 URL(nullable)
 */
public record CartItemView(
        String variantPublicId,
        Integer quantity,
        Boolean selected,
        String productName,
        String sellerName,
        long displayPrice,
        int quantityAvailable,
        boolean purchasable,
        String thumbnailUrl) {
}
