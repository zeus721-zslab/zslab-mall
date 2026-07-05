package com.zslab.mall.settlement.exception;

/**
 * 동일 seller·정산 기간의 Settlement가 이미 존재해 중복 생성이 거부됐을 때 발생한다(Track 48 P3). 전역적으로 409로 매핑된다.
 *
 * <p>정상 재실행은 {@code existsBySellerIdAndPeriodStartAndPeriodEnd} 선확인으로 seller별 skip 처리되어 본 예외가
 * 발생하지 않는다(멱등). 본 예외는 선확인 통과 후 saveAndFlush 시점에 {@code DataIntegrityViolationException}
 * (uk_settlement_seller_period 위반)이 터지는 <b>동시 배치 실행 레이스</b> 백스톱이며, 배치 트랜잭션을 롤백시키고 409를 반환한다.
 *
 * <p>{@code RuntimeException + String message} 단순 패턴을 유지한다(도메인 예외 관례 정합).
 */
public class SettlementAlreadyExistsException extends RuntimeException {

    public SettlementAlreadyExistsException(String message) {
        super(message);
    }
}
