package com.zslab.mall.delivery.exception;

/**
 * 송장 정정 시 다른 배송 행이 이미 같은 송장번호를 쓰고 있을 때 발생한다(Track 89-B·{@code uk_delivery_tracking_no}).
 * 서비스가 저장 전에 검사해 던지며(UK 위반 500 차단) 전역 예외 핸들러가 409(CONFLICT)로 응답한다. 같은 행의 기존 번호로
 * 다시 저장하는 경우는 충돌이 아니다.
 */
public class DeliveryTrackingNoConflictException extends RuntimeException {

    public DeliveryTrackingNoConflictException(String message) {
        super(message);
    }
}
