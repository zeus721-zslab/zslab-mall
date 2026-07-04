package com.zslab.mall.cart.exception;

/**
 * 로그인 buyer의 장바구니에 대상 variant가 담겨 있지 않을 때 발생한다(Track 45 수량변경·selected 토글). 대상키 variantId가
 * userId 스코프 조회에서 미매칭 시 던진다(타 buyer 소유 항목도 조회 불가라 동일 404로 은닉·소유권 정보 노출 회피).
 *
 * <p>product 도메인의 {@code ProductVariantNotFoundException}("상품 변형 자체가 없음")과 의미가 달라(장바구니에 안 담김)
 * 재사용하지 않고 cart 전용 예외로 둔다. 전역 예외 핸들러가 HTTP 404로 응답한다.
 */
public class CartItemNotFoundException extends RuntimeException {

    public CartItemNotFoundException(String message) {
        super(message);
    }
}
