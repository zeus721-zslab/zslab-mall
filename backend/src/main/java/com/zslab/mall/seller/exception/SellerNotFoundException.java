package com.zslab.mall.seller.exception;

/** 관리자 상품 관리에서 sellerPublicId(slr_)에 해당하는 판매자가 없을 때 발생한다(Track 76·404 SELLER_NOT_FOUND). */
public class SellerNotFoundException extends RuntimeException {

    public SellerNotFoundException(String message) {
        super(message);
    }
}
