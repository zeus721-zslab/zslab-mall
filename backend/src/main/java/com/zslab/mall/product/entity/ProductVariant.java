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
 * 상품 변형/SKU(PRD 종속·SOFT·public_id {@code var_}). <b>Track 4 read-only</b>(D-59) — 응답 enrich(§11 variantId)·
 * 재결제 재검증(D-51·D-60 {@code is_soldout_manual})의 조회 전용으로 최소 신설한다. 쓰기 책임은 Track 7 이연.
 *
 * <p>{@link AbstractPublicIdSoftDeletableEntity} 상속. {@code option1~3_value_id}는 FK 연관(@ManyToOne) 없이 read-only 컬럼만
 * 보유한다(D-59·조인 미수행). 정적 팩토리·setter·도메인 행위를 두지 않는다. {@code @SQLRestriction}은 Hibernate 6.6
 * HHH-17453 버그로 {@code @MappedSuperclass}에서 {@code @Entity}로 전파되지 않아 본 클래스에 직접 선언한다(LT-03·D-82·D-86).
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

    @Override
    protected String getPublicIdPrefix() {
        return "var";
    }
}
