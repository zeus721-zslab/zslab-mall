package com.zslab.mall.product.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 셀러 상품 이미지 등록 요청(Track 59 BL-6). imageUrl은 필수이며 길이 상한은 product_image.image_url 컬럼을 SoT로 반영한다.
 * main=true면 등록과 동시에 대표로 지정되고 기존 대표는 강등된다(Service demote-then-set). 형식 검증만 담당하고 소유권·
 * 대표 단일성은 Service가 강제한다.
 */
public record AddProductImageRequest(
        @NotBlank @Size(max = 2048) String imageUrl, // SoT: product_image.image_url VARCHAR(2048)
        boolean main) {
}
