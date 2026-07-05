package com.zslab.mall.settlement.exception;

/**
 * 정산을 찾을 수 없을 때 발생한다(Track 49). 전역 예외 핸들러가 HTTP 404로 응답한다
 * (OrderNotFoundException 선례 정합).
 *
 * <p>대표 케이스: {@code SettlementTransitionService.confirm/pay}에서 전이 대상 settlementId가 존재하지 않는 경우.
 */
public class SettlementNotFoundException extends RuntimeException {

    public SettlementNotFoundException(String message) {
        super(message);
    }
}
