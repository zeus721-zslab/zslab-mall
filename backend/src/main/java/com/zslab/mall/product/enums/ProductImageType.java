package com.zslab.mall.product.enums;

/**
 * 상품 이미지 유형(Track 76·V21). DDL {@code product_image.image_type} ENUM 정합.
 *
 * <ul>
 *   <li>GALLERY — 순서(display_order)·대표(is_main) 대상 이미지. 목록 썸네일·상세 상단 갤러리.</li>
 *   <li>DETAIL — 상세 영역 이미지. 대표 지정 대상이 아니다.</li>
 * </ul>
 * {@code is_main} 플래그와의 혼동을 피하기 위해 'MAIN'이 아닌 'GALLERY'로 명명한다. 업로드·서빙은 Track 77.
 */
public enum ProductImageType {
    GALLERY,
    DETAIL
}
