package com.zslab.mall.seller.exception;

/**
 * 정지(SUSPENDED) 셀러의 쓰기 요청 차단(Track 90-A·403 SELLER_SUSPENDED). SUSPENDED는 유효한 세션 상태이며 해당 행위(쓰기)만
 * 금지되므로, 인증 실패(401)가 아니라 인가 거부(403)로 응답한다. PENDING·TERMINATED는 인증 자체가 무효라
 * {@code UnauthenticatedException}(401)로 처리한다.
 */
public class SellerSuspendedException extends RuntimeException {

    public SellerSuspendedException(String message) {
        super(message);
    }
}
