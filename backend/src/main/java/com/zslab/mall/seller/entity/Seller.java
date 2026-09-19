package com.zslab.mall.seller.entity;

import com.zslab.mall.common.entity.AbstractPublicIdSoftDeletableEntity;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.settlement.service.CommissionRateResolver;
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
 * 최초 owner). 상태 전이는 {@link #changeStatus}(Track 89-D·D-187·SLR-4), 정보 수정은 {@link #update}가 담당한다.
 *
 * <p>{@link AbstractPublicIdSoftDeletableEntity} 상속(full audit + soft-delete 3컬럼 + public_id slr_). setter를 두지 않는다. {@code @SQLRestriction}은 Hibernate 6.6 HHH-17453 버그로 {@code @MappedSuperclass}에서
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
     * 셀러 개별 계약 수수료율·basis-point(1000 = 10.00%). NULL=개별 계약 없음(카테고리율 → 플랫폼 기본율·Track 85·V30).
     * 판정은 {@code CommissionRateResolver}가 하며 정산은 주문 시점 order_item 스냅샷만 쓴다. 편집은 {@link #update}(Track 89-D).
     */
    @Column(name = "commission_rate")
    private Integer commissionRate;

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
        // 입점 시 개별 계약율 없음(NULL) → 카테고리/플랫폼 기본율 적용(Track 85·V30). 개별율은 update로 편집(Track 89-D).
        return seller;
    }

    /**
     * 상태 전이(SLR-4·state-machine.md §7). 합법 여부는 {@link SellerStatus#canTransitionTo}가 판정하며 같은 상태 재요청도
     * 불법이다. TERMINATED 진입 시 WithdrawnSeller 행 생성(SLR-6)은 Service 책임이다.
     *
     * @throws IllegalStateException 불법 전이(TERMINATED에서의 전이·같은 상태 재요청 포함)
     */
    public void changeStatus(SellerStatus next) {
        if (next == null || !this.status.canTransitionTo(next)) {
            throw new IllegalStateException("불법 판매자 상태 전이: " + this.status + " → " + next);
        }
        this.status = next;
    }

    /**
     * 판매자 정보 수정(Track 89-D). companyName·ceoName은 NOT NULL 컬럼이라 null 가드하며 형식·길이는 DTO {@code @Valid} 책임이다.
     * commissionRate는 basis-point·null = 개별 계약 없음. 범위(0~10000)는 저장 전에 재검증한다({@code Category.update} 선례 —
     * 범위 밖 값이 저장되면 체크아웃 판정 단계에서 그 셀러 상품이 전부 차단되므로 DTO 검증을 우회한 경로도 막는다).
     *
     * @throws IllegalArgumentException companyName·ceoName 누락·commissionRate 범위 밖
     */
    public void update(
            String companyName,
            String businessNo,
            String ceoName,
            String contactEmail,
            String contactPhone,
            Integer commissionRate) {
        if (companyName == null || companyName.isBlank() || ceoName == null || ceoName.isBlank()) {
            throw new IllegalArgumentException("Seller 필수값 누락(companyName·ceoName).");
        }
        if (commissionRate != null) {
            CommissionRateResolver.requireInRange(commissionRate, "Seller.commissionRate");
        }
        this.companyName = companyName;
        this.businessNo = businessNo;
        this.ceoName = ceoName;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
        this.commissionRate = commissionRate;
    }

    @Override
    protected String getPublicIdPrefix() {
        return "slr";
    }
}
