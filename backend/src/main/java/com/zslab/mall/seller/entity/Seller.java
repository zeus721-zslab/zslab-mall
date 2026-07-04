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
import org.hibernate.annotations.SQLRestriction;

/**
 * 판매자(SLR Aggregate Root·SOFT·public_id {@code slr_}). Track 4(D-59)에서 응답 enrich(§11 sellerId·companyName)
 * 조회 전용으로 신설했다. 입점 INSERT 쓰기는 Track 37 provisioning 경로에서 {@link #create}로 도입한다(관리자 주도·
 * 최초 owner). 정지·해지 등 상태 전이 쓰기는 후속 트랙 이연.
 *
 * <p>{@link AbstractPublicIdSoftDeletableEntity} 상속(full audit + soft-delete 3컬럼 + public_id slr_). 정적 팩토리·setter·
 * 도메인 행위를 두지 않는다. {@code @SQLRestriction}은 Hibernate 6.6 HHH-17453 버그로 {@code @MappedSuperclass}에서
 * {@code @Entity}로 전파되지 않아 본 클래스에 직접 선언한다(LT-03·D-82).
 */
@Entity
@Table(name = "seller")
@SQLRestriction("deleted_at IS NULL")
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

    /**
     * 판매자 입점 레코드를 생성한다(Track 37 provisioning·관리자 주도). NOT NULL 컬럼(companyName·ceoName·status)만
     * null 가드하며, nullable 컬럼(businessNo·contactEmail·contactPhone)은 가드하지 않는다({@link SellerUser#create}
     * 스타일 정합·형식/필수 검증은 DTO {@code @Valid} 책임). public_id는 {@code @PrePersist}에서 생성된다.
     *
     * @throws IllegalArgumentException companyName·ceoName·status 중 누락 시
     */
    public static Seller create(
            String companyName,
            String businessNo,
            String ceoName,
            String contactEmail,
            String contactPhone,
            SellerStatus status) {
        if (companyName == null || ceoName == null || status == null) {
            throw new IllegalArgumentException("Seller 필수값 누락(companyName·ceoName·status).");
        }
        Seller seller = new Seller();
        seller.companyName = companyName;
        seller.businessNo = businessNo;
        seller.ceoName = ceoName;
        seller.contactEmail = contactEmail;
        seller.contactPhone = contactPhone;
        seller.status = status;
        return seller;
    }

    @Override
    protected String getPublicIdPrefix() {
        return "slr";
    }
}
