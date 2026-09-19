package com.zslab.mall.seller.exception;

/** 셀러 구성원 상태 위반(Track 89-G·422 SELLER_MEMBER_INVALID_STATE): 이미 같은 역할인 구성원의 역할 변경 재요청(같은 상태 재요청 422 관습·D-187·D-188). */
public class SellerMemberInvalidStateException extends RuntimeException {
    public SellerMemberInvalidStateException(String message) {
        super(message);
    }
}
