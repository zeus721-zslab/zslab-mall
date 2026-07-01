package com.zslab.mall.delivery.exception;

/**
 * 배송 행을 찾을 수 없을 때 발생한다. {@code AdminDeliveryController}가 deliveryPublicId 미매칭 시 던진다(D-104 §5·404 매핑).
 */
public class DeliveryNotFoundException extends RuntimeException {

    public DeliveryNotFoundException(String message) {
        super(message);
    }
}
