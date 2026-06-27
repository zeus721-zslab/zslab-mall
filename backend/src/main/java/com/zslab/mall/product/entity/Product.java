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

/**
 * 상품(PRD Aggregate Root·SOFT·public_id {@code prd_}). <b>Track 4 read-only</b>(D-59) — 응답 enrich(§11 productId·
 * D-55 previewTitle name)·재결제 재검증(D-51·D-60 status)의 조회 전용으로 최소 신설한다. 등록·승인·상태 전이 등 쓰기 책임은 Track 7 이연.
 *
 * <p>{@link AbstractPublicIdSoftDeletableEntity} 상속(full audit + soft-delete 3컬럼 + public_id prd_). 정적 팩토리·setter·
 * 도메인 행위를 두지 않는다. 베이스 {@code @SQLRestriction("deleted_at IS NULL")}로 soft-delete 행은 조회에서 자동 제외된다.
 */
@Entity
@Table(name = "product")
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

    @Override
    protected String getPublicIdPrefix() {
        return "prd";
    }
}
