package com.zslab.mall.common.enums;

/**
 * polymorphic target_type 공유 Enum(D-86 Q4).
 *
 * <p>Attachment·AuditLog·NotificationLog의 target_type VARCHAR(50) 컬럼에 공통 사용. D-01 Aggregate 목록 기반 단일 소스.
 * DDL은 VARCHAR(50)·D분류(앱 검증)이므로 DB ENUM 아님 — Java Enum + DTO @ValidEnum은 Track 8+ 이연.
 * 자기 참조(예: attachment.target_type=ATTACHMENT)는 사용 컨텍스트에서 자연 제외.
 */
public enum PolymorphicTargetType {

    ORDER,
    ORDER_ITEM,
    PAYMENT,
    DELIVERY,
    CLAIM,
    REFUND,
    USER,
    SELLER,
    PRODUCT,
    PRODUCT_VARIANT,
    CART_ITEM,
    SETTLEMENT,
    SETTLEMENT_BANK_ACCOUNT,
    CATEGORY,
    INVENTORY,
    ATTACHMENT,
    AUDIT_LOG,
    NOTIFICATION_LOG,
    CODE,
    BUYER_GRADE
}
