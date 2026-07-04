package com.zslab.mall.product.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 상품 변형/SKU 등록 요청(Track 39 provisioning·중첩). {@code optionKeys}는 optionGroups 하위 값의 임시키(key)를 참조하며
 * (DB id 아님·option1~3ValueId 매핑과 TempKey 해소는 후속 오케스트레이션 책임), status·is_soldout_manual은 서버가 고정하므로
 * 요청에 두지 않는다. @Size 상한은 ProductVariant 컬럼 길이를 SoT로 반영한다.
 */
public record ProductVariantRequest(
        @NotBlank @Size(max = 50) String variantCode, // SoT: ProductVariant.variantCode @Column(length=50)
        @Size(max = 100) String sellerSku, // nullable — SoT: ProductVariant.sellerSku @Column(length=100)
        @Size(max = 100) String barcode, // nullable — SoT: ProductVariant.barcode @Column(length=100)
        @NotNull @PositiveOrZero Long additionalPrice,
        @PositiveOrZero int displayOrder,
        @PositiveOrZero int initialStock, // 0 허용(품절 의미 정의는 본 트랙 범위 아님·재고 0 허용만)
        List<@NotBlank String> optionKeys) { // 빈 배열 허용 = 단순상품 variant(구조 검증은 ProductRegistrationService)
}
