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
    public static final String PICKUP_CONFIRMED = "TPL_PICKUP_CONFIRMED";
    public static final String CLAIM_REQUESTED = "TPL_CLAIM_REQUESTED";
    public static final String CLAIM_REJECTED = "TPL_CLAIM_REJECTED";
    /** 구매자 철회(BUYER_WITHDRAWN) 사유의 클레임 종결 SMS(Track 101-A). 거부와 상태 전이는 같지만 구매자에게 읽히는 의미가 다르다. */
    public static final String CLAIM_CANCELLED = "TPL_CLAIM_CANCELLED";
    /** 관리자 임시 비밀번호 SMS(Track 84·본문은 마스킹 저장). */
    public static final String TEMPORARY_PASSWORD = "TPL_TEMPORARY_PASSWORD";
    /** 정산 정상처리 셀러 SMS(Track 85). */
    public static final String SETTLEMENT_CONFIRMED = "TPL_SETTLEMENT_CONFIRMED";

    private NotificationTemplateCodes() {
    }
}
