package com.zslab.mall.settlement.exception;

/**
 * 지급 완료 처리 시 셀러의 주 정산계좌(is_primary)가 없을 때(Track 85·422 SETTLEMENT_BANK_ACCOUNT_MISSING). 계좌는 생성 조건이 아니라
 * 지급 조건이며 지급 시점 계좌를 스냅샷한다(STL-3).
 */
public class SettlementBankAccountMissingException extends RuntimeException {

    public SettlementBankAccountMissingException(String message) {
        super(message);
    }
}
