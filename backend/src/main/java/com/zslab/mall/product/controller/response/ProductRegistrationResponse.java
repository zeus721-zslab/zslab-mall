package com.zslab.mall.product.controller.response;

import java.util.List;

/**
 * 상품 등록 응답(Track 39 provisioning). 생성된 product public_id와 variant public_id 목록만 노출한다(필요 최소·과잉 금지).
 */
public record ProductRegistrationResponse(String productPublicId, List<String> variantPublicIds) {
}
