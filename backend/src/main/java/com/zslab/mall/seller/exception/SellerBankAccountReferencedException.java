package com.zslab.mall.seller.exception;

/**
 * 정산이 참조하는 정산계좌 행의 수정 차단(Track 89-F·D-188·409 SELLER_BANK_ACCOUNT_REFERENCED). settlement.bank_account_id가 이 행을
 * 가리키면 지급 이력 스냅샷이므로 in-place 수정 시 정산 상세가 변조된다 → 새 계좌를 등록하고 주 계좌를 전환해야 한다.
 */
public class SellerBankAccountReferencedException extends RuntimeException {

    public SellerBankAccountReferencedException(String message) {
        super(message);
    }
}
