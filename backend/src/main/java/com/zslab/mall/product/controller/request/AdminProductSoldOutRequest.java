package com.zslab.mall.product.controller.request;

import jakarta.validation.constraints.NotNull;

/** 상품 단위 수동 품절 on/off 요청(Track 76). */
public record AdminProductSoldOutRequest(@NotNull Boolean soldOut) {
}
