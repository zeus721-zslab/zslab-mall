package com.zslab.mall.product.repository;

import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.enums.ProductVariantStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * 관리자 상품 목록 동적 필터(Track 76). 필터 조합이 5축(검색·상태·품절·셀러·카테고리)이라 JPQL CASE 분기 대신 Criteria
 * {@link Specification}으로 조립한다(프로젝트 첫 Specification 사용·D-165). 모든 값은 Criteria 파라미터 바인딩이며 문자열 SQL 조립이
 * 없다(SQL injection 위험 없음). soft-delete 제외는 {@code @SQLRestriction}이 자동 적용한다.
 *
 * <p><b>품절 필터</b>의 정의는 {@code ProductPurchasePolicy.isSoldOut}과 동일하다: 상품 수동품절 ∨ "SALE ∧ ¬수동품절 ∧ 가용재고&gt;0인
 * variant"가 하나도 없음. 응답의 soldOut 플래그도 같은 정책으로 계산하므로 필터와 표기가 어긋나지 않는다.
 */
public final class AdminProductSpecifications {

    private AdminProductSpecifications() {
    }

    /** 검색: 상품명 부분일치(escape된 LIKE 패턴) 또는 public_id 정확일치. 둘 다 null이면 조건 없음. */
    public static Specification<Product> keyword(String likePattern, String rawKeyword) {
        return (root, query, builder) -> {
            if (likePattern == null) {
                return null;
            }
            return builder.or(
                    builder.like(root.get("name"), likePattern, '\\'),
                    builder.equal(root.get("publicId"), rawKeyword));
        };
    }

    public static Specification<Product> status(ProductStatus status) {
        return (root, query, builder) -> status == null ? null : builder.equal(root.get("status"), status);
    }

    public static Specification<Product> sellerId(Long sellerId) {
        return (root, query, builder) -> sellerId == null ? null : builder.equal(root.get("sellerId"), sellerId);
    }

    public static Specification<Product> categoryId(Long categoryId) {
        return (root, query, builder) -> categoryId == null ? null : builder.equal(root.get("categoryId"), categoryId);
    }

    /** 품절 여부 필터(null=조건 없음). 정의는 클래스 Javadoc 참조. */
    public static Specification<Product> soldOut(Boolean soldOut) {
        return (root, query, builder) -> {
            if (soldOut == null) {
                return null;
            }
            Subquery<Long> purchasableVariant = query.subquery(Long.class);
            Root<ProductVariant> variant = purchasableVariant.from(ProductVariant.class);
            Root<Inventory> inventory = purchasableVariant.from(Inventory.class);
            purchasableVariant.select(variant.get("id")).where(
                    builder.equal(variant.get("productId"), root.get("id")),
                    builder.equal(inventory.get("variantId"), variant.get("id")),
                    builder.equal(variant.get("status"), ProductVariantStatus.SALE),
                    builder.isFalse(variant.get("soldoutManual")),
                    builder.greaterThan(inventory.get("quantityAvailable"), 0));

            List<Predicate> soldOutPredicates = new ArrayList<>();
            soldOutPredicates.add(builder.isTrue(root.get("soldoutManual")));
            soldOutPredicates.add(builder.not(builder.exists(purchasableVariant)));
            Predicate isSoldOut = builder.or(soldOutPredicates.toArray(new Predicate[0]));
            return soldOut ? isSoldOut : builder.not(isSoldOut);
        };
    }
}
