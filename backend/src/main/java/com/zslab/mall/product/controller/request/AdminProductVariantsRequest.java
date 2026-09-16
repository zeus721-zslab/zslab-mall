package com.zslab.mall.product.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 관리자 variant 전체 치환 요청(Track 76·D9 α·PUT). 기존 옵션 그룹 구조는 유지하며 (1) variantPublicId가 있으면 메타 수정
 * (옵션 조합 불변·options 무시) (2) 없으면 options로 조합을 해소해 신규 생성(옵션값은 (그룹·값)으로 찾거나 새로 만든다·initialStock
 * 초기 재고) (3) 목록에 없는 기존 variant는 soft-delete. 최소 1개는 남아야 한다(Service 검증·400).
 */
public record AdminProductVariantsRequest(@NotEmpty @Valid List<Item> variants) {

    public record Item(
            String variantPublicId, // null=신규
            @NotBlank @Size(max = 50) String variantCode,
            @Size(max = 100) String sellerSku,
            @Size(max = 100) String barcode,
            @NotNull @PositiveOrZero Long additionalPrice,
            @NotBlank @Pattern(regexp = "^(SALE|HIDDEN|STOPPED)$", message = "status는 SALE·HIDDEN·STOPPED만 허용합니다.")
            String status,
            boolean soldoutManual,
            @PositiveOrZero int displayOrder,
            @PositiveOrZero int initialStock, // 신규 variant 전용(기존은 무시·재고 조정은 inventories/adjust)
            @Valid List<Option> options) { // 신규 variant 전용·단순상품은 빈 목록
    }

    public record Option(@NotNull Long optionGroupId, @NotBlank @Size(max = 100) String value) {
    }
}
