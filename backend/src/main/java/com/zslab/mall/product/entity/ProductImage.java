package com.zslab.mall.product.entity;

import com.zslab.mall.common.entity.AbstractSoftDeletableEntity;
import com.zslab.mall.product.enums.ProductImageType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "image_type", nullable = false)
    private ProductImageType imageType;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "is_main", nullable = false)
    private boolean main;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static ProductImage create(Product product, String imageUrl, int displayOrder, boolean main) {
        return create(product, imageUrl, ProductImageType.GALLERY, displayOrder, main);
    }

    /**
     * 이미지 유형을 지정해 생성한다(Track 76). 셀러 경로({@link #create(Product, String, int, boolean)})는 GALLERY 고정이다.
     *
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static ProductImage create(
            Product product, String imageUrl, ProductImageType imageType, int displayOrder, boolean main) {
        if (product == null || imageUrl == null || imageUrl.isBlank() || imageType == null) {
            throw new IllegalArgumentException("ProductImage 필수값 누락(product·imageUrl·imageType).");
        }
        ProductImage image = new ProductImage();
        image.product = product;
        image.imageUrl = imageUrl;
        image.imageType = imageType;
        image.displayOrder = displayOrder;
        image.main = main;
        return image;
    }

    /**
     * 관리자 이미지 메타 수정(Track 76·URL·유형·순서·대표). 업로드 자체는 Track 77이며 여기서는 메타만 바꾼다.
     *
     * @throws IllegalArgumentException imageUrl 공백·imageType null 시
     */
    public void updateMeta(String imageUrl, ProductImageType imageType, int displayOrder, boolean main) {
        if (imageUrl == null || imageUrl.isBlank() || imageType == null) {
            throw new IllegalArgumentException("ProductImage 필수값 누락(imageUrl·imageType).");
        }
        this.imageUrl = imageUrl;
        this.imageType = imageType;
        this.displayOrder = displayOrder;
        this.main = main;
    }

    /** 대표 이미지로 지정한다(Track 59 BL-6·designateMain의 승격 단계·기존 대표 강등 후 호출·UserAddress.markDefault 선례). */
    public void markMain() {
        this.main = true;
    }

    /** 대표 이미지 지정을 해제한다(Track 59 BL-6·demote-then-set의 강등 단계·UserAddress.unmarkDefault 선례). */
    public void unmarkMain() {
        this.main = false;
    }

    /** 정렬 순서를 변경한다(Track 59 BL-6·reorder에서 imageIds 순서대로 0..n-1 재배치). */
    public void changeDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }
}
