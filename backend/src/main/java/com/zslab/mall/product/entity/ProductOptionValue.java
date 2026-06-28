package com.zslab.mall.product.entity;

import com.zslab.mall.common.entity.AbstractFullAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 옵션값(PRD 종속·SOFT 상속·public_id 없음).
 *
 * <p>{@link AbstractFullAuditableEntity} 상속(full audit 4컬럼). DDL deleted_at 없음·"SOFT 상속"은
 * Product Root의 soft-delete 정책을 상속받는다는 의미(D-12). optionGroup은 Product Aggregate 내부
 * 엔티티 — D-86 Q1 정합으로 @ManyToOne LAZY 허용. soft-delete 컬럼 없으므로 LT-03 불필요.
 */
@Entity
@Table(name = "product_option_value")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOptionValue extends AbstractFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_group_id", nullable = false, updatable = false)
    private ProductOptionGroup optionGroup;

    @Column(name = "value", nullable = false, length = 100)
    private String value;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static ProductOptionValue create(ProductOptionGroup optionGroup, String value, int displayOrder) {
        if (optionGroup == null || value == null || value.isBlank()) {
            throw new IllegalArgumentException("ProductOptionValue 필수값 누락(optionGroup·value).");
        }
        ProductOptionValue optionValue = new ProductOptionValue();
        optionValue.optionGroup = optionGroup;
        optionValue.value = value;
        optionValue.displayOrder = displayOrder;
        return optionValue;
    }
}
