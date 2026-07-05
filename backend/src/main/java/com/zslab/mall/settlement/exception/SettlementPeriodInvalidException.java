package com.zslab.mall.settlement.exception;

/**
 * 정산 배치 요청의 기간(year/month)이 유효 범위를 벗어났을 때 발생한다(Track 48 P3). 전역적으로 400으로 매핑된다.
 *
 * <p>대표 케이스: month가 1~12가 아니거나 year가 비합리적 범위(2000~2100 밖). 형식 검증이 아니라 정산 가능 기간이라는
 * 도메인 규칙이므로 Service 레이어에서 던진다({@code IllegalArgumentException} 직접 노출 대신 전용 예외로 code 명확화).
 *
 * <p>{@code RuntimeException + String message} 단순 패턴을 유지한다(도메인 예외 관례 정합).
 */
public class SettlementPeriodInvalidException extends RuntimeException {

    public SettlementPeriodInvalidException(String message) {
        super(message);
    }
}
