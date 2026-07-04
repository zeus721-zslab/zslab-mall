package com.zslab.mall.product.entity;

import com.zslab.mall.common.entity.AbstractPublicIdSoftDeletableEntity;
import com.zslab.mall.product.enums.ProductVariantStatus;
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
 * 상품 변형/SKU(PRD 종속·SOFT·public_id {@code var_}). Track 4(D-59)에서 응답 enrich(§11 variantId)·재결제 재검증
 * (D-51·D-60 {@code is_soldout_manual}) 조회 전용으로 신설했다. 등록 INSERT 쓰기는 Track 39 provisioning 경로에서
 * {@link #create}로 도입한다(status는 SALE·is_soldout_manual은 false 서버 고정). 상태 전이 쓰기는 후속 트랙 이연.
 *
 * <p>{@link AbstractPublicIdSoftDeletableEntity} 상속. {@code option1~3_value_id}는 FK 연관(@ManyToOne) 없이 read-only 컬럼만
 * 보유한다(D-59·조인 미수행). 생성 전용 {@link #create} 외 setter·상태 전이 도메인 행위를 두지 않는다. {@code @SQLRestriction}은
 * Hibernate 6.6 HHH-17453 버그로 {@code @MappedSuperclass}에서 {@code @Entity}로 전파되지 않아 본 클래스에 직접 선언한다(LT-03·D-82·D-86).
 */
@Entity
@Table(name = "product_variant")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductVariant extends AbstractPublicIdSoftDeletableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "variant_code", nullable = false, length = 50)
    private String variantCode;

    @Column(name = "seller_sku", length = 100)
    private String sellerSku;

    @Column(name = "barcode", length = 100)
    private String barcode;

    @Column(name = "additional_price", nullable = false)
    private Long additionalPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProductVariantStatus status;

    @Column(name = "is_soldout_manual", nullable = false)
    private boolean soldoutManual;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "option1_value_id", nullable = false)
    private Long option1ValueId;

    @Column(name = "option2_value_id")
    private Long option2ValueId;

    @Column(name = "option3_value_id")
    private Long option3ValueId;

    /**
     * 상품 변형/SKU 레코드를 생성한다(Track 39 provisioning·seller 주도). NOT NULL 컬럼(productId·variantCode·additionalPrice·
     * option1ValueId)만 null 가드하며, nullable 컬럼(sellerSku·barcode·option2ValueId·option3ValueId)은 가드하지 않는다
     * (Product.create 스타일 정합·형식/필수 검증은 DTO {@code @Valid} 책임). status는 {@link ProductVariantStatus#SALE}·
     * is_soldout_manual은 false로 서버 고정한다. public_id는 {@code @PrePersist}에서 생성된다.
     *
     * @throws IllegalArgumentException productId·variantCode·additionalPrice·option1ValueId 중 누락 시
     */
    public static ProductVariant create(
            Long productId,
            String variantCode,
            String sellerSku,
            String barcode,
            Long additionalPrice,
            int displayOrder,
            Long option1ValueId,
            Long option2ValueId,
            Long option3ValueId) {
        if (productId == null || variantCode == null || additionalPrice == null || option1ValueId == null) {
            throw new IllegalArgumentException(
                    "ProductVariant 필수값 누락(productId·variantCode·additionalPrice·option1ValueId).");
        }
        ProductVariant variant = new ProductVariant();
        variant.productId = productId;
        variant.variantCode = variantCode;
        variant.sellerSku = sellerSku;
        variant.barcode = barcode;
        variant.additionalPrice = additionalPrice;
        variant.status = ProductVariantStatus.SALE;
        variant.soldoutManual = false;
        variant.displayOrder = displayOrder;
        variant.option1ValueId = option1ValueId;
        variant.option2ValueId = option2ValueId;
        variant.option3ValueId = option3ValueId;
        return variant;
    }

    @Override
    protected String getPublicIdPrefix() {
        return "var";
    }
}
