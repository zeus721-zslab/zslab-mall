package com.zslab.mall.product.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 상품 옵션값 등록 요청(Track 39 provisioning·중첩). {@code key}는 요청 본문 내에서만 유효한 클라이언트 지정 임시키이며,
 * variant가 {@code optionKeys}로 이를 참조한다(DB id 아님·TempKey 해소는 후속 오케스트레이션 책임). @Size 상한은
 * ProductOptionValue.value 컬럼 길이를 SoT로 반영한다.
 */
public record ProductOptionValueRequest(
        @NotBlank String key,
        @NotBlank @Size(max = 100) String value, // SoT: ProductOptionValue.value @Column(length=100)
        @PositiveOrZero int displayOrder) {
}
