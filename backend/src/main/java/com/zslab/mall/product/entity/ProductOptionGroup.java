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
 * 상품 옵션 그룹(PRD 종속·SOFT 상속·public_id 없음).
 *
 * <p>{@link AbstractFullAuditableEntity} 상속(full audit 4컬럼). DDL deleted_at 없음·"SOFT 상속"은
 * Product Root의 soft-delete 정책을 상속받는다는 의미(D-12·recon §7 WARN-1 해소). product는 Product
 * Aggregate 내부 엔티티 — D-86 Q1 정합으로 @ManyToOne LAZY 허용. soft-delete 컬럼 없으므로 LT-03 불필요.
 */
@Entity
@Table(name = "product_option_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOptionGroup extends AbstractFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static ProductOptionGroup create(Product product, String name, int displayOrder) {
        if (product == null || name == null || name.isBlank()) {
            throw new IllegalArgumentException("ProductOptionGroup 필수값 누락(product·name).");
        }
        ProductOptionGroup group = new ProductOptionGroup();
        group.product = product;
        group.name = name;
        group.displayOrder = displayOrder;
        return group;
    }
}
