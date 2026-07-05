package com.zslab.mall.settlement.exception;

/**
 * 정산 상태 전이를 진행할 수 없는 상태일 때 발생한다(Track 49). 전역적으로 422로 매핑된다
 * (OrderItemInvalidStateException·DeliveryInvalidStateException·ClaimInvalidStateException 선례 정합·D-50).
 *
 * <p>대표 케이스: {@code SettlementTransitionService.confirm/pay}에서 정산이 순방향 전이 대상 상태가 아니어서
 * (예: PENDING인 정산에 지급 시도·PAID인 정산에 확정 시도) 전이가 불가한 경우. 도메인 전이 위반
 * ({@link IllegalStateException})을 본 예외로 흡수해 500 fallback을 차단한다(직접 IllegalStateException 매핑 금지).
 * 상태 위반이 settlement 도메인 사건이므로 타 도메인 InvalidState 예외 재사용(code 오분류) 대신 settlement 전용 예외를 둔다.
 *
 * <p>선례와 동일한 {@code RuntimeException + String message} 단순 패턴을 유지한다.
 */
public class SettlementInvalidStateException extends RuntimeException {

    public SettlementInvalidStateException(String message) {
        super(message);
    }
}
