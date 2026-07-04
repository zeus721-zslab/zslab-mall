package com.zslab.mall.product.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 상품 옵션 그룹 등록 요청(Track 39 provisioning·중첩). @Size 상한은 ProductOptionGroup.name 컬럼 길이를 SoT로 반영한다.
 */
public record ProductOptionGroupRequest(
        @NotBlank @Size(max = 50) String name, // SoT: ProductOptionGroup.name @Column(length=50)
        @PositiveOrZero int displayOrder,
        @NotEmpty @Valid List<ProductOptionValueRequest> values) {
}
