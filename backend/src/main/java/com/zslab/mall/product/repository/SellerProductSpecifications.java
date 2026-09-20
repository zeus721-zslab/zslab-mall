package com.zslab.mall.product.repository;

import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.enums.ProductStatus;
import org.springframework.data.jpa.domain.Specification;

/**
 * 셀러 상품 목록 필터(Track 90-C-1·{@code SellerOrderItemSpecifications} 패턴). 루트는 {@link Product}이며
 * {@link #sellerId}는 항상 AND로 결합해 자기 상품만 조회한다(인덱스 ix_product_seller_status·V21). soft-delete 제외는
 * {@code @SQLRestriction}이 자동 적용한다. 관리자 {@code AdminProductSpecifications}는 재사용하지 않는다(셀러 계약 독립).
 * 모든 값은 Criteria 파라미터 바인딩이며 문자열 SQL 조립이 없다(SQL injection 위험 없음).
 */
public final class SellerProductSpecifications {

    private SellerProductSpecifications() {
    }

    /** 자기 상품만(product.seller_id). 목록·상세 공통의 소유 조건이며 null을 허용하지 않는다. */
    public static Specification<Product> sellerId(Long sellerId) {
        return (root, query, builder) -> builder.equal(root.get("sellerId"), sellerId);
    }

    /** 상품 public_id 정확일치(상세 조회·소유 조건과 AND로 결합해 타 셀러 상품은 미존재와 같은 404). */
    public static Specification<Product> publicId(String publicId) {
        return (root, query, builder) -> builder.equal(root.get("publicId"), publicId);
    }

    /** 검색: 상품명 부분일치(escape된 LIKE 패턴). null이면 조건 없음. */
    public static Specification<Product> keyword(String likePattern) {
        return (root, query, builder) -> likePattern == null ? null : builder.like(root.get("name"), likePattern, '\\');
    }

    public static Specification<Product> status(ProductStatus status) {
        return (root, query, builder) -> status == null ? null : builder.equal(root.get("status"), status);
    }

    public static Specification<Product> categoryId(Long categoryId) {
        return (root, query, builder) -> categoryId == null ? null : builder.equal(root.get("categoryId"), categoryId);
    }
}
