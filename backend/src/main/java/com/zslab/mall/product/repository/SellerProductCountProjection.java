package com.zslab.mall.product.repository;

import com.zslab.mall.product.enums.ProductStatus;

/** 셀러별·상태별 활성 상품 수 projection(Track 89-D 관리자 셀러 목록·상세). */
public interface SellerProductCountProjection {

    Long getSellerId();

    ProductStatus getStatus();

    Long getProductCount();
}
