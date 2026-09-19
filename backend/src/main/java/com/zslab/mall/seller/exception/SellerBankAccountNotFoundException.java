package com.zslab.mall.seller.exception;

/** 셀러 정산계좌 미존재 또는 다른 셀러 소속(Track 89-F·404 SELLER_BANK_ACCOUNT_NOT_FOUND·타 셀러 계좌는 존재 은닉). */
public class SellerBankAccountNotFoundException extends RuntimeException {

    public SellerBankAccountNotFoundException(String message) {
        super(message);
    }
}
