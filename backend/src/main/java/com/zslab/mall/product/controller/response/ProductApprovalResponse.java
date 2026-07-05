package com.zslab.mall.product.controller.response;

import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.enums.ProductStatus;

/**
 * 상품 승인·거부 전이 결과 응답(Track 50). 전이 후 상품 public_id와 상태를 노출한다
 * (SettlementTransitionResponse 선례 정합).
 */
public record ProductApprovalResponse(
        String productPublicId,
        ProductStatus status) {

    public static ProductApprovalResponse from(Product product) {
        return new ProductApprovalResponse(product.getPublicId(), product.getStatus());
    }
}
