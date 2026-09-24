package com.zslab.mall.product.controller.request;

/**
 * 구매자 카탈로그 목록 정렬 기준(Track 44·요청 파라미터). DB 영속 컬럼이 아닌 조회 파라미터라 4층위 enum 잠금 대상이 아니다.
 *
 * <ul>
 *   <li>{@link #LATEST} — 최신 등록순(created_at DESC·기본값)</li>
 *   <li>{@link #PRICE_ASC} — 대표가 오름차순(basePrice + 판매가능 variant MIN(additional_price))</li>
 *   <li>{@link #PRICE_DESC} — 대표가 내림차순</li>
 *   <li>{@link #NAME} — 상품명 오름차순</li>
 *   <li>{@link #SALES} — 최근 7일 판매 수량 내림차순(결제 완료 이후·취소/반품 종결 제외·동률은 최신순·D-221)</li>
 * </ul>
 */
public enum ProductCatalogSort {
    LATEST,
    PRICE_ASC,
    PRICE_DESC,
    NAME,
    SALES
}
