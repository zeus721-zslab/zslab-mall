package com.zslab.mall.product.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 일괄 수동 품절 변경 요청(Track 76). 항목 수 상한 100. */
public record AdminProductBulkSoldOutRequest(
        @NotEmpty @Size(max = 100) List<@NotBlank String> productPublicIds,
        @NotNull Boolean soldOut) {
}
