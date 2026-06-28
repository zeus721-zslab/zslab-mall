package com.zslab.mall.product.entity;

import com.zslab.mall.common.entity.AbstractSoftDeletableEntity;
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
import org.hibernate.annotations.SQLRestriction;

/**
 * 상품 이미지(PRD 종속·SOFT·public_id 없음).
 *
 * <p>{@link AbstractSoftDeletableEntity} 상속(full audit + soft-delete 3컬럼). product는 Product Aggregate
 * 내부 엔티티 — D-01 내부 참조·D-86 Q1 정합으로 @ManyToOne LAZY 허용.
 *
 * <p>{@code @SQLRestriction}은 Hibernate 6.6 HHH-17453 버그로 {@code @MappedSuperclass}에서
 * {@code @Entity}로 전파되지 않아 본 클래스에 직접 선언한다(LT-03·D-82·D-86).
 */
@Entity
@Table(name = "product_image")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImage extends AbstractSoftDeletableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "image_url", nullable = false, length = 2048)
    private String imageUrl;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "is_main", nullable = false)
    private boolean main;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static ProductImage create(Product product, String imageUrl, int displayOrder, boolean main) {
        if (product == null || imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("ProductImage 필수값 누락(product·imageUrl).");
        }
        ProductImage image = new ProductImage();
        image.product = product;
        image.imageUrl = imageUrl;
        image.displayOrder = displayOrder;
        image.main = main;
        return image;
    }
}
