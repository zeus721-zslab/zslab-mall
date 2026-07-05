package com.zslab.mall.product.controller.response;

/**
 * 셀러 상품 이미지 응답(Track 59 BL-6). 셀러 관리 화면용으로 내부 id를 노출한다 — 구매자 카탈로그 {@code
 * ProductDetailResponse.Image}(id 미노출)와 소비 주체·계약이 달라 재사용하지 않는다. displayOrder·대표 여부(main)를 포함한다.
 */
public record ProductImageResponse(
        Long id,
        String imageUrl,
        int displayOrder,
        boolean main) {
}
