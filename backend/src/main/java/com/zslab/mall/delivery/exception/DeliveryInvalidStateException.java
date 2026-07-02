package com.zslab.mall.delivery.exception;

/**
 * 배송 개시(출고)를 진행할 수 없는 상태일 때 발생한다(Track 23). 전역적으로 422로 매핑된다(ClaimInvalidStateException 선례 정합·D-50).
 *
 * <p>대표 케이스: {@code OrderShippingService.prepareShipment}에서 OrderItem이 PAID가 아니어서 PREPARING 전이가 불가한 경우
 * (예: 이미 SHIPPING인 품목에 중복 출고 시도). 도메인 전이 위반({@link IllegalStateException})을 본 예외로 흡수해
 * 500 fallback을 차단한다(직접 IllegalStateException 매핑 금지).
 *
 * <p>{@code ClaimInvalidStateException}·{@code RefundInvariantViolationException}과 동일한 {@code RuntimeException + String message}
 * 단순 패턴을 유지한다.
 */
public class DeliveryInvalidStateException extends RuntimeException {

    public DeliveryInvalidStateException(String message) {
        super(message);
    }
}
