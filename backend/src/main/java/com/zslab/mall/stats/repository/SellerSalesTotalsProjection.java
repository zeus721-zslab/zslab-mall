package com.zslab.mall.stats.repository;

/**
 * 셀러 기간 매출 집계 1행(Track 90-E-1). orderCount는 자기 품목이 포함된 주문 수(COUNT DISTINCT order·D-192 정의), revenue는 자기 품목
 * total_price 합, quantity는 자기 품목 수량 합. 행 0건이어도 COALESCE로 0 행 1개가 온다.
 */
public interface SellerSalesTotalsProjection {
    Long getOrderCount();
    Long getRevenue();
    Long getQuantity();
}
