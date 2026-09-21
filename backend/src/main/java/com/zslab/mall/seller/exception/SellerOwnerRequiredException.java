package com.zslab.mall.seller.exception;

/**
 * SELLER_OWNER 한정 셀러 쓰기 요청을 다른 셀러 역할(MANAGER·STAFF)이 호출했을 때(Track 90-D-3·403 SELLER_OWNER_REQUIRED).
 * 셀러 세션 자체는 유효하고 해당 행위만 역할상 금지되므로 {@code SellerSuspendedException}과 같이 인가 거부(403)로 응답한다.
 */
public class SellerOwnerRequiredException extends RuntimeException {
    public SellerOwnerRequiredException(String message) {
        super(message);
    }
}
