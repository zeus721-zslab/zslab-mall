package com.zslab.mall.product.repository;

/**
 * variant 옵션 조합(option1~3 value id) projection(Track 90-C 검토 반영). soft-delete된 variant까지 포함해 조합 중복을 앱 레벨에서
 * 선검증하는 데 쓴다 — {@code uk_product_variant_options}는 option2/3 NULL 조합에서 발동하지 않으므로(MariaDB NULL distinct)
 * 1·2슬롯 상품의 삭제 조합 재생성은 이 조회로만 막을 수 있다.
 */
public interface VariantOptionCombinationProjection {

    Long getOption1ValueId();

    Long getOption2ValueId();

    Long getOption3ValueId();
}
