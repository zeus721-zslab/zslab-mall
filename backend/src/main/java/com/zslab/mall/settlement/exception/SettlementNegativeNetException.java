package com.zslab.mall.settlement.exception;

/**
 * 정산액(net)이 음수인 정산을 지급 완료 처리하려 할 때(Track 85·422 SETTLEMENT_NET_NEGATIVE). 음수 정산은 저장·확정은 허용하되
 * 지급은 차단하고, 확정된 음수 정산의 부족분은 다음 정산 생성 시 이월(CARRYOVER) 차감 품목으로 편입한다(Track 104-3b ⑧).
 */
public class SettlementNegativeNetException extends RuntimeException {

    public SettlementNegativeNetException(String message) {
        super(message);
    }
}
