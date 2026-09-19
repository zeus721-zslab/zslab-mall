package com.zslab.mall.seller.exception;

/** 정산계좌 상태 위반(Track 89-F·422 SELLER_BANK_ACCOUNT_INVALID_STATE): 이미 주 계좌인 행의 주 계좌 전환 재요청(같은 상태 재요청 422 관습·D-187). */
public class SellerBankAccountInvalidStateException extends RuntimeException {

    public SellerBankAccountInvalidStateException(String message) {
        super(message);
    }
}
