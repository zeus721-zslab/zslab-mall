package com.zslab.mall.seller.enums;

/**
 * 판매자 정산계좌 인증 상태(A#4·SELLER_BANK_ACCOUNT_STATUS·3값). DDL {@code seller_bank_account.status} ENUM 정합.
 */
public enum SellerBankAccountStatus {
    PENDING,
    VERIFIED,
    REJECTED
}
