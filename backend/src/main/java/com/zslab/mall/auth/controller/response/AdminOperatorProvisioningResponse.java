package com.zslab.mall.auth.controller.response;

/** 운영 관리자(ADMIN_OPERATOR) 공급 응답. 역할을 부여받은 대상 회원의 public_id만 노출한다(SellerProvisioningResponse 패턴). (Track 38) */
public record AdminOperatorProvisioningResponse(String userPublicId) {
}
