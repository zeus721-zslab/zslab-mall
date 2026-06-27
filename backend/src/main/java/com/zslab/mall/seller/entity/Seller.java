package com.zslab.mall.seller.entity;

import com.zslab.mall.common.entity.AbstractPublicIdSoftDeletableEntity;
import com.zslab.mall.seller.enums.SellerStatus;
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
 * 판매자(SLR Aggregate Root·SOFT·public_id {@code slr_}). <b>Track 4 read-only</b>(D-59) — 응답 enrich(§11 sellerId·
 * companyName)의 조회 전용으로 최소 신설한다. 입점·정지·해지 등 쓰기 책임은 Track 7 이연.
 *
 * <p>{@link AbstractPublicIdSoftDeletableEntity} 상속(full audit + soft-delete 3컬럼 + public_id slr_). 정적 팩토리·setter·
 * 도메인 행위를 두지 않는다. 베이스 {@code @SQLRestriction("deleted_at IS NULL")}로 soft-delete 행은 조회에서 자동 제외된다.
 */
@Entity
@Table(name = "seller")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seller extends AbstractPublicIdSoftDeletableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 100)
    private String companyName;

    @Column(name = "business_no", length = 20)
    private String businessNo;

    @Column(name = "ceo_name", nullable = false, length = 50)
    private String ceoName;

    @Column(name = "contact_email", length = 254)
    private String contactEmail;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SellerStatus status;

    @Override
    protected String getPublicIdPrefix() {
        return "slr";
    }
}
