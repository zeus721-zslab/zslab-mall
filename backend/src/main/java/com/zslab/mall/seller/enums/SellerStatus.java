package com.zslab.mall.seller.enums;

/**
 * 판매자 상태(B분류·SELLER_STATUS·SLR-4·4값). DDL {@code seller.status} ENUM 정합.
 *
 * <p><b>Track 4 사용</b>: 컬럼 매핑 목적 신설(D-59). 현 트랙 응답 enrich는 {@code company_name}만 사용하며 status는
 * 로직 미사용(Track 7 판매자 도메인 확장 대비 매핑 보존).
 */
public enum SellerStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    TERMINATED
}
