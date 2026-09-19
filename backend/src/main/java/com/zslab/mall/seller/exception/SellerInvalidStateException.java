package com.zslab.mall.seller.exception;

/**
 * 판매자 상태 위반(Track 89-D·422 SELLER_INVALID_STATE): 불법 상태 전이(같은 상태 재요청·TERMINATED 이후 전이 등)와
 * 비-ACTIVE 판매자에 대한 관리자 상품 등록 차단에 쓴다({@code ProductInvalidStateException} 선례).
 */
public class SellerInvalidStateException extends RuntimeException {

    public SellerInvalidStateException(String message) {
        super(message);
    }
}
