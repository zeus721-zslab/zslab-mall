package com.zslab.mall.notification.template;

/**
 * 알림 템플릿 코드 상수(Track 12·D-95 WARN-10-α). 이벤트별 templateCode 매핑을 단일 위치에 응집한다.
 *
 * <p>후속 트랙(RETURN/EXCHANGE·Delivery·Inventory) 진입 시 누적 매핑을 본 클래스에 추가한다.
 * Enum 승격은 ≥10건 누적 또는 DTO 검증 수요 발생 시점에 결정한다(WARN-10-β 기각·DTO @ValidEnum 동반 회피).
 */
public final class NotificationTemplateCodes {

    public static final String ORDER_PLACED = "TPL_ORDER_PLACED";
    public static final String PAYMENT_COMPLETED = "TPL_PAYMENT_COMPLETED";
    public static final String CLAIM_APPROVED = "TPL_CLAIM_APPROVED";
    public static final String CLAIM_COMPLETED = "TPL_CLAIM_COMPLETED";
    public static final String REFUND_FAILED = "TPL_REFUND_FAILED";
    public static final String DELIVERY_STARTED = "TPL_DELIVERY_STARTED";
    public static final String DELIVERY_COMPLETED = "TPL_DELIVERY_COMPLETED";

    private NotificationTemplateCodes() {
    }
}
