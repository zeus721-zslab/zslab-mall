package com.zslab.mall.stats.repository;

/** 상품별 수량 집계 1행(Track 90-E-3·입고 합·현재 가용 합). keyId는 product.id. */
public interface SellerProductQuantityProjection {
    Long getKeyId();
    Long getQuantity();
}
