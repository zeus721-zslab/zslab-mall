package com.zslab.mall.seller.controller.response;

/** 판매자 입점 provisioning 응답. 생성된 seller public_id만 노출한다(SignupResponse 패턴). (Track 37) */
public record SellerProvisioningResponse(String sellerPublicId) {
}
