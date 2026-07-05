package com.zslab.mall.order.exception;

/**
 * OrderItem 상태 전이를 진행할 수 없는 상태일 때 발생한다(Track 47). 전역적으로 422로 매핑된다
 * (ClaimInvalidStateException·DeliveryInvalidStateException 선례 정합·D-50).
 *
 * <p>대표 케이스: {@code BuyerOrderConfirmService.confirmPurchase}에서 OrderItem이 DELIVERED가 아니어서 CONFIRMED 전이가
 * 불가한 경우(예: 아직 SHIPPING인 품목에 구매확정 시도). 도메인 전이 위반({@link IllegalStateException})을 본 예외로 흡수해
 * 500 fallback을 차단한다(직접 IllegalStateException 매핑 금지). 상태 위반이 order 도메인 사건이므로 delivery 도메인의
 * {@code DeliveryInvalidStateException} 재사용(code 오분류) 대신 order 전용 예외를 둔다.
 *
 * <p>{@code DeliveryInvalidStateException}·{@code ClaimInvalidStateException}과 동일한 {@code RuntimeException + String message}
 * 단순 패턴을 유지한다.
 */
public class OrderItemInvalidStateException extends RuntimeException {

    public OrderItemInvalidStateException(String message) {
        super(message);
    }
}
