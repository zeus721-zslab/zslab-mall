package com.zslab.mall.stats.repository;

/**
 * 셀러 옵션 축 집계 1행(Track 90-E-1). keyId는 order_item.variant_id, productName·optionLabel은 주문 시점 스냅샷(MAX·옵션 없는 단순상품은
 * optionLabel null). orderCount는 주문 건수(DISTINCT order), quantity는 품목 수량 합.
 */
public interface SellerSalesOptionProjection {
    Long getKeyId();
    String getProductName();
    String getOptionLabel();
    Long getRevenue();
    Long getOrderCount();
    Long getQuantity();
}
