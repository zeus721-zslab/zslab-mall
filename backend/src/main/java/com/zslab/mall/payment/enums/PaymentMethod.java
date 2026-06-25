package com.zslab.mall.payment.enums;

/**
 * 결제수단(A분류·A#9 잠금·4값). DDL {@code payment.method} ENUM 정합.
 *
 * <p>A분류는 코드 변경 없이 값 추가가 불가한 잠금 집합이다(V1 DDL ENUM과 1:1). 신규 수단 도입 시 Flyway 마이그레이션·enum 동시 변경이 필요하다.
 */
public enum PaymentMethod {
    CARD,
    BANK,
    VBANK,
    KAKAO
}
