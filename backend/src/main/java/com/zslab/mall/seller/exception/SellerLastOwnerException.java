package com.zslab.mall.seller.exception;

/**
 * 마지막 활성 SELLER_OWNER 제거·강등 차단(Track 89-G·409 SELLER_LAST_OWNER). 대상이 활성(탈퇴·삭제 아님) OWNER이고 그 외 활성
 * OWNER가 없을 때 발생한다. 마지막 SUPER_ADMIN 회수 차단({@code LastSuperAdminRevocationException}·Track 53) 동형.
 */
public class SellerLastOwnerException extends RuntimeException {
    public SellerLastOwnerException(String message) {
        super(message);
    }
}
