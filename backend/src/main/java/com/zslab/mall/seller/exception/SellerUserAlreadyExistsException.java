package com.zslab.mall.seller.exception;

/**
 * user_id 단독 UNIQUE(V12·"1 user = 1 seller") 위반 — 이미 다른 판매자에 소속된 사용자를 다시 소속시키려 할 때 발생한다
 * (Track 37 판매자 provisioning). 전역 예외 핸들러가 HTTP 409(CONFLICT)로 응답한다.
 */
public class SellerUserAlreadyExistsException extends RuntimeException {

    public SellerUserAlreadyExistsException(String message) {
        super(message);
    }
}
