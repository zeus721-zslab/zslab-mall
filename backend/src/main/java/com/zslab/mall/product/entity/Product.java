package com.zslab.mall.product.entity;

import com.zslab.mall.common.entity.AbstractPublicIdSoftDeletableEntity;
import com.zslab.mall.product.enums.ProductStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 상품(PRD Aggregate Root·SOFT·public_id {@code prd_}). Track 4(D-59)에서 응답 enrich(§11 productId·D-55 previewTitle name)·
 * 재결제 재검증(D-51·D-60 status) 조회 전용으로 신설했다. 등록 INSERT 쓰기는 Track 39 provisioning 경로에서 {@link #create}로
 * 도입한다(seller 주도·status는 SALE 서버 고정). 승인·상태 전이 등 상태 쓰기는 후속 트랙 이연.
 *
 * <p>{@link AbstractPublicIdSoftDeletableEntity} 상속(full audit + soft-delete 3컬럼 + public_id prd_). 생성 전용 {@link #create}
 * 외 setter·상태 전이 도메인 행위를 두지 않는다. {@code @SQLRestriction}은 Hibernate 6.6 HHH-17453 버그로
 * {@code @MappedSuperclass}에서 {@code @Entity}로 전파되지 않아 본 클래스에 직접 선언한다(LT-03·D-82·D-86).
 */
@Entity
@Table(name = "product")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends AbstractPublicIdSoftDeletableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "LONGTEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProductStatus status;

    @Column(name = "base_price", nullable = false)
    private Long basePrice;

    @Column(name = "thumbnail_url", length = 2048)
    private String thumbnailUrl;

    /**
     * 상품 등록 레코드를 생성한다(Track 39 provisioning·seller 주도). NOT NULL 컬럼(sellerId·categoryId·name·basePrice)만
     * null 가드하며, nullable 컬럼(description·thumbnailUrl)은 가드하지 않는다(Seller.create 스타일 정합·형식/필수 검증은
     * DTO {@code @Valid} 책임). status는 입력받지 않고 {@link ProductStatus#SALE}로 서버 고정한다. public_id는
     * {@code @PrePersist}에서 생성된다.
     *
     * @throws IllegalArgumentException sellerId·categoryId·name·basePrice 중 누락 시
     */
    public static Product create(
            Long sellerId,
            Long categoryId,
            String name,
            String description,
            Long basePrice,
            String thumbnailUrl) {
        if (sellerId == null || categoryId == null || name == null || basePrice == null) {
            throw new IllegalArgumentException("Product 필수값 누락(sellerId·categoryId·name·basePrice).");
        }
        Product product = new Product();
        product.sellerId = sellerId;
        product.categoryId = categoryId;
        product.name = name;
        product.description = description;
        product.status = ProductStatus.SALE;
        product.basePrice = basePrice;
        product.thumbnailUrl = thumbnailUrl;
        return product;
    }

    @Override
    protected String getPublicIdPrefix() {
        return "prd";
    }
}
