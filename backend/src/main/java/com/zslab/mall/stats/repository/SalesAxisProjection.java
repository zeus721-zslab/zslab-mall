package com.zslab.mall.stats.repository;

/**
 * 분해 축 집계 1행(Track 87). keyId는 축 내부 식별자(categoryId·sellerId·productId), name은 PRODUCT 축의 order_item.product_name
 * 스냅샷(MAX)이며 다른 축은 null(서비스가 enrich). orderCount는 주문 건수(DISTINCT order), quantity는 품목 수량 합.
 */
public interface SalesAxisProjection {

    Long getKeyId();

    String getName();

    Long getRevenue();

    Long getOrderCount();

    Long getQuantity();
}
