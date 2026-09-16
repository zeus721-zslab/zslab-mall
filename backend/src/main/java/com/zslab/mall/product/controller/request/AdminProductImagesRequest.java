package com.zslab.mall.product.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 관리자 이미지 메타 전체 치환 요청(Track 76·PUT). 목록 순서가 display_order(0..n-1)가 되며, 목록에 없는 기존 이미지는
 * soft-delete된다. imageId null=신규. 업로드 자체는 Track 77이며 여기서는 URL·유형·순서·대표만 다룬다.
 * 대표(main)는 GALLERY 1장 이하(Service 검증·400).
 */
public record AdminProductImagesRequest(@NotNull @Valid List<Item> images) {

    public record Item(
            Long imageId,
            @NotBlank @Size(max = 2048) String imageUrl,
            @NotBlank @Pattern(regexp = "^(GALLERY|DETAIL)$", message = "imageType은 GALLERY 또는 DETAIL만 허용합니다.")
            String imageType,
            boolean main) {
    }
}
