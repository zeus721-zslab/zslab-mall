package com.zslab.mall.stats.repository;

/**
 * 셀러 클레임 상품별 분해 1행(Track 90-E-2). keyId는 order_item.product_id, productName은 주문 시점 스냅샷(MAX), claimCount는 기간 내 요청
 * (requested_at) 클레임 건수.
 */
public interface SellerClaimProductProjection {
    Long getKeyId();
    String getProductName();
    Long getClaimCount();
}
