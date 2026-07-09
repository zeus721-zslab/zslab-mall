package com.zslab.mall.cart.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 장바구니 품목 삭제 요청(Track 45). 외부 대상키 variantPublicId 배열(var_·단건도 1개 배열). userId는 인증 컨텍스트
 * 해소·요청 제외. buyer 스코프 물리삭제(deleteByUserIdAndVariantPublicIdIn)이므로 타 buyer 항목은 삭제 대상에 포함되지
 * 않는다(소유권 자동).
 *
 * @param variantPublicIds 삭제할 상품 변형 외부 식별자 목록(최소 1개·각 var_)
 */
public record CartItemDeleteRequest(
        @NotEmpty List<@NotBlank @Size(max = 30) String> variantPublicIds) {
}
