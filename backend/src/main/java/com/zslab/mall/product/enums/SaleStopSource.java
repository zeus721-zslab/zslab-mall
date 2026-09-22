package com.zslab.mall.product.enums;

/**
 * 판매중지 주체(A분류·2값·Track 96-5·D-206). DDL {@code product.sale_stop_source} ENUM 정합.
 *
 * <p>불변식: {@code Product.status = STOPPED} ↔ {@code saleStopSource != null}. {@code ADMIN} 중지는 관리자만 재판매(SALE 복귀)할 수 있고,
 * {@code SELLER} 중지는 셀러 본인·관리자 모두 재판매할 수 있다. 재판매 시 null로 돌아간다.
 */
public enum SaleStopSource {
    ADMIN,
    SELLER
}
