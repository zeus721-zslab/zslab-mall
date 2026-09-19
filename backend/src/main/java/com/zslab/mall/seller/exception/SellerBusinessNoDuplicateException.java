package com.zslab.mall.seller.exception;

/** 사업자등록번호 중복(Track 89-D·409 SELLER_BUSINESS_NO_DUPLICATE·uk_seller_business_no·SLR-1). 입점·정보 수정 공통. */
public class SellerBusinessNoDuplicateException extends RuntimeException {

    public SellerBusinessNoDuplicateException(String message) {
        super(message);
    }
}
