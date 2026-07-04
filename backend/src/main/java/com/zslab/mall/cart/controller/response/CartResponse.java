package com.zslab.mall.cart.controller.response;

import java.util.List;

/**
 * 장바구니 조회 응답(Track 45). buyer의 장바구니 품목 전량을 래핑한다(페이징 없음·장바구니 소규모). 담김 없으면 items는 빈 목록.
 *
 * @param items 장바구니 품목 뷰 목록
 */
public record CartResponse(List<CartItemView> items) {
}
