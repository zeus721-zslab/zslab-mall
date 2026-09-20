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
 * 셀러 variant 메타 수정 + 신규 추가 요청(Track 90-C-2·PUT). (1) variantPublicId가 있으면 메타 수정(옵션 조합 불변·options·initialStock
 * 무시) (2) 없으면 options로 기존 옵션값 조합을 해소해 신규 생성(initialStock 초기 재고). 관리자와 달리 목록에 없는 기존 variant는
 * 건드리지 않는다(삭제 없음·비활성화는 status=HIDDEN). 옵션값은 기존 값만 참조하며 새로 만들지 않는다(옵션 구조 불변).
 * @Size 상한은 ProductVariant 컬럼 길이를 SoT로 반영한다.
 */
public record SellerProductVariantsRequest(@NotEmpty @Valid List<Item> variants) {

    public record Item(
            String variantPublicId, // null=신규
            @NotBlank @Size(max = 50) String variantCode, // SoT: ProductVariant.variantCode @Column(length=50)
            @Size(max = 100) String sellerSku,
            @Size(max = 100) String barcode,
            @NotNull @PositiveOrZero Long additionalPrice,
            @NotBlank @Pattern(regexp = "^(SALE|HIDDEN|STOPPED)$", message = "status는 SALE·HIDDEN·STOPPED만 허용합니다.")
            String status,
            boolean soldoutManual,
            @PositiveOrZero int displayOrder,
            @PositiveOrZero int initialStock, // 신규 variant 전용(기존은 무시·재고 변경은 mark-inbound/outbound)
            @Valid List<Option> options) { // 신규 variant 전용·단순상품은 빈 목록
    }

    public record Option(@NotNull Long optionGroupId, @NotBlank @Size(max = 100) String value) {
    }
}
