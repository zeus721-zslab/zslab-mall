package com.zslab.mall.product.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 관리자 상품 등록 요청(Track 76). 셀러 등록 요청({@link ProductRegistrationRequest})에 sellerPublicId(셀러 지정)와 관리자 전용
 * 부가 필드(공급가·판매기간)를 더한 형태다. 옵션·variant 구조 검증은 {@code ProductRegistrationService}가 그대로 수행한다.
 * 상태는 셀러 등록과 동일하게 PENDING으로 생성되며(승인은 approve 경로), 판매기간 시각은 ISO offset(예 2026-09-16T00:00:00+09:00)이다.
 */
public record AdminProductCreateRequest(
        @NotBlank String sellerPublicId,
        @NotNull Long categoryId,
        @NotBlank @Size(max = 200) String name,
        String description,
        @NotNull @PositiveOrZero Long basePrice,
        @PositiveOrZero Long supplyPrice, // nullable — 표시용 공급가(D-165 D7 α)
        @Size(max = 2048) String thumbnailUrl,
        OffsetDateTime saleStartAt, // nullable = 즉시
        OffsetDateTime saleEndAt, // nullable = 무기한
        @Valid List<ProductOptionGroupRequest> optionGroups,
        @NotEmpty @Valid List<ProductVariantRequest> variants) {

    /** 셀러 등록 Service 재사용을 위한 변환(관리자 전용 필드 제외). */
    public ProductRegistrationRequest toRegistrationRequest() {
        return new ProductRegistrationRequest(
                categoryId, name, description, basePrice, thumbnailUrl, optionGroups, variants);
    }
}
