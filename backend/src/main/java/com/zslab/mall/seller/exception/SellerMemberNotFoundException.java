package com.zslab.mall.seller.exception;

/**
 * 셀러 구성원 미존재(Track 89-G·404 SELLER_MEMBER_NOT_FOUND): 해당 셀러에 소속되지 않은 user를 제거·역할 변경하려는 경우.
 * 타 셀러 소속·미소속을 구분하지 않고 404로 은닉한다(계좌 404 선례·D-188).
 */
public class SellerMemberNotFoundException extends RuntimeException {
    public SellerMemberNotFoundException(String message) {
        super(message);
    }
}
