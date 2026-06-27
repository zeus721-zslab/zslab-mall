package com.zslab.mall.product.enums;

/**
 * 상품 변형 상태(A분류·A#7·3값). DDL {@code product_variant.status} ENUM 정합.
 *
 * <p><b>Track 4 사용</b>: 컬럼 매핑 목적 신설(D-59). 재결제 재검증(D-60)의 품절 판정은 {@code is_soldout_manual}·재고로
 * 수행하며 본 status는 현 트랙 로직 미사용(Track 7 확장 대비 매핑 보존).
 */
public enum ProductVariantStatus {
    SALE,
    HIDDEN,
    STOPPED
}
