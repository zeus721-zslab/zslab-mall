package com.zslab.mall.settlement.controller.response;

import com.zslab.mall.seller.entity.SellerBankAccount;

/**
 * 정산 상세 계좌(Track 85). 계좌번호는 끝 4자리만 노출한다. snapshot=true면 지급 시점 스냅샷(settlement.bank_account_id), false면 현재
 * 주 계좌(미지급 정산 참고용).
 */
public record SettlementBankAccountResponse(
        Long id,
        String bankCode,
        String accountHolder,
        String accountNumberSuffix,
        boolean snapshot) {

    private static final int SUFFIX_LENGTH = 4;

    public static SettlementBankAccountResponse of(SellerBankAccount account, boolean snapshot) {
        String number = account.getAccountNumber() == null ? "" : account.getAccountNumber();
        String suffix = number.length() <= SUFFIX_LENGTH ? number : number.substring(number.length() - SUFFIX_LENGTH);
        return new SettlementBankAccountResponse(account.getId(), account.getBankCode(), account.getAccountHolder(),
                suffix, snapshot);
    }
}
