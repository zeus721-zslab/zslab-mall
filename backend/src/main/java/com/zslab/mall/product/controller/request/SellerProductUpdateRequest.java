package com.zslab.mall.product.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 셀러 상품 기본정보 수정 요청(Track 90-C-2·전체 치환 PUT). 셀러가 바꿀 수 있는 4필드만 받는다 — 공급가·판매기간·상태·셀러는
 * 관리자 소관이라 요청에 두지 않으며 서비스가 현재 값을 보존한다. @Size 상한은 Product 컬럼 길이를 SoT로 반영한다.
 */
public record SellerProductUpdateRequest(
        @NotNull Long categoryId,
        @NotBlank @Size(max = 200) String name, // SoT: Product.name @Column(length=200)
        String description, // nullable — LONGTEXT
        @NotNull @PositiveOrZero Long basePrice) {
}
