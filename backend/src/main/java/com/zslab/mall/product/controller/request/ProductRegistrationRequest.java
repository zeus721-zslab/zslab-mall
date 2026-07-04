package com.zslab.mall.product.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 상품 등록 요청(Track 39 provisioning·seller 주도·중첩). sellerId는 인증 컨텍스트에서 해소하고 status·is_soldout_manual은
 * 서버가 고정하므로 요청에 두지 않는다. categoryId는 Category가 public_id 미부여 엔티티라 DB id(Long)로 받는다. @Size 상한은
 * Product 컬럼 길이를 SoT로 반영하며, 도메인 규칙(옵션-변형 정합·TempKey 해소)은 후속 오케스트레이션이 검증한다.
 */
public record ProductRegistrationRequest(
        @NotNull Long categoryId, // Category는 public_id 미부여 → DB id 직접 참조
        @NotBlank @Size(max = 200) String name, // SoT: Product.name @Column(length=200)
        String description, // nullable — SoT: Product.description LONGTEXT(길이 무제한·@Size 미부여)
        @NotNull @PositiveOrZero Long basePrice,
        @Size(max = 2048) String thumbnailUrl, // nullable — SoT: Product.thumbnailUrl @Column(length=2048)
        @Valid List<ProductOptionGroupRequest> optionGroups, // 빈 배열 허용 = 단순상품(Service가 DEFAULT 옵션 합성·구조 검증 Service 이관)
        @NotEmpty @Valid List<ProductVariantRequest> variants) {
}
