package com.zslab.mall.cart.controller.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 장바구니 품목 삭제 요청(Track 45). 대상키 variantId 배열(단건도 1개 배열). userId는 인증 컨텍스트 해소·요청 제외.
 * buyer 스코프 물리삭제(deleteByUserIdAndVariantIdIn)이므로 타 buyer 항목은 삭제 대상에 포함되지 않는다(소유권 자동).
 *
 * @param variantIds 삭제할 상품 변형 식별자 목록(최소 1개)
 */
public record CartItemDeleteRequest(
        @NotEmpty List<Long> variantIds) {
}
