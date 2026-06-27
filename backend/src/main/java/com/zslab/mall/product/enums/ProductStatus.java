package com.zslab.mall.product.enums;

/**
 * 상품 상태(A분류·A#6·7값). DDL {@code product.status} ENUM 정합.
 *
 * <p><b>Track 4 사용</b>: 재결제 재검증(D-51·D-60)에서 {@code != SALE} 판정에만 사용한다. 상태 전이·승인 흐름은 Track 7 이연.
 */
public enum ProductStatus {
    DRAFT,
    PENDING,
    APPROVED,
    REJECTED,
    SALE,
    HIDDEN,
    STOPPED
}
