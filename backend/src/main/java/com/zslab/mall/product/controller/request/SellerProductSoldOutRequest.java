package com.zslab.mall.product.controller.request;

import jakarta.validation.constraints.NotNull;

/** 셀러 상품 단위 수동 품절 on/off 요청(Track 96-5·관리자 {@link AdminProductSoldOutRequest} 동형). */
public record SellerProductSoldOutRequest(@NotNull Boolean soldOut) {
}
