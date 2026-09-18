package com.zslab.mall.settlement.exception;

/**
 * 정산액(net)이 음수인 정산을 지급 완료 처리하려 할 때(Track 85·422 SETTLEMENT_NET_NEGATIVE). 음수 정산은 저장·정상처리는 허용하되
 * 지급은 차감 이월 정책이 도입될 때까지 차단한다.
 */
public class SettlementNegativeNetException extends RuntimeException {

    public SettlementNegativeNetException(String message) {
        super(message);
    }
}
