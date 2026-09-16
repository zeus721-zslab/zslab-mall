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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 상품(PRD Aggregate Root·SOFT·public_id {@code prd_}). Track 4(D-59)에서 응답 enrich(§11 productId·D-55 previewTitle name)·
 * 재결제 재검증(D-51·D-60 status) 조회 전용으로 신설했다. 등록 INSERT 쓰기는 Track 39 provisioning 경로에서 {@link #create}로
 * 도입한다(seller 주도·status는 PENDING 서버 고정). 운영자 승인 시 SALE, 거부 시 REJECTED로 전이한다
 * ({@link #approve}·{@link #reject}·Track 50).
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

    @Column(name = "is_soldout_manual", nullable = false)
    private boolean soldoutManual;

    @Column(name = "base_price", nullable = false)
    private Long basePrice;

    @Column(name = "supply_price")
    private Long supplyPrice;

    @Column(name = "thumbnail_url", length = 2048)
    private String thumbnailUrl;

    @Column(name = "sale_start_at")
    private LocalDateTime saleStartAt;

    @Column(name = "sale_end_at")
    private LocalDateTime saleEndAt;

    /**
     * 상품 등록 레코드를 생성한다(Track 39 provisioning·seller 주도). NOT NULL 컬럼(sellerId·categoryId·name·basePrice)만
     * null 가드하며, nullable 컬럼(description·thumbnailUrl)은 가드하지 않는다(Seller.create 스타일 정합·형식/필수 검증은
     * DTO {@code @Valid} 책임). status는 입력받지 않고 {@link ProductStatus#PENDING}으로 서버 고정한다(운영자 승인 시 SALE 전이·Track 50). public_id는
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
        product.status = ProductStatus.PENDING;
        product.soldoutManual = false;
        product.basePrice = basePrice;
        product.thumbnailUrl = thumbnailUrl;
        return product;
    }

    /**
     * 관리자 기본정보 수정(Track 76). sellerId·status는 본 메서드로 바꾸지 않는다(셀러는 order_item.seller_id 스냅샷과 충돌·
     * 상태는 전이 mutator 전용). NOT NULL 컬럼(categoryId·name·basePrice)만 null 가드하며 형식 검증은 DTO {@code @Valid} 책임이다.
     *
     * @throws IllegalArgumentException 필수값 누락 또는 판매 시작 시각이 종료 시각 이후일 때
     */
    public void updateBasicInfo(
            Long categoryId,
            String name,
            String description,
            Long basePrice,
            Long supplyPrice,
            String thumbnailUrl,
            LocalDateTime saleStartAt,
            LocalDateTime saleEndAt) {
        if (categoryId == null || name == null || basePrice == null) {
            throw new IllegalArgumentException("Product 필수값 누락(categoryId·name·basePrice).");
        }
        assertSalePeriod(saleStartAt, saleEndAt);
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.basePrice = basePrice;
        this.supplyPrice = supplyPrice;
        this.thumbnailUrl = thumbnailUrl;
        this.saleStartAt = saleStartAt;
        this.saleEndAt = saleEndAt;
    }

    /**
     * 등록 직후 관리자 전용 부가 필드(공급가·판매기간)를 채운다(Track 76·셀러 등록 Service 재사용 후 같은 트랜잭션에서 호출).
     *
     * @throws IllegalArgumentException 판매 시작 시각이 종료 시각 이후일 때
     */
    public void applySaleTerms(Long supplyPrice, LocalDateTime saleStartAt, LocalDateTime saleEndAt) {
        assertSalePeriod(saleStartAt, saleEndAt);
        this.supplyPrice = supplyPrice;
        this.saleStartAt = saleStartAt;
        this.saleEndAt = saleEndAt;
    }

    /** 상품 단위 수동 품절 on/off(Track 76). 같은 값 재설정은 no-op이다. */
    public void changeSoldoutManual(boolean soldoutManual) {
        this.soldoutManual = soldoutManual;
    }

    /**
     * 판매기간 판정(Track 76·ProductPurchasePolicy가 호출). 시작 NULL=즉시·종료 NULL=무기한이며 종료 시각은 배타다
     * ({@code start <= now < end}).
     */
    public boolean isWithinSalePeriod(LocalDateTime now) {
        boolean started = saleStartAt == null || !saleStartAt.isAfter(now);
        boolean notEnded = saleEndAt == null || saleEndAt.isAfter(now);
        return started && notEnded;
    }

    private static void assertSalePeriod(LocalDateTime saleStartAt, LocalDateTime saleEndAt) {
        if (saleStartAt != null && saleEndAt != null && !saleStartAt.isBefore(saleEndAt)) {
            throw new IllegalArgumentException("판매 시작 시각은 종료 시각보다 앞서야 합니다.");
        }
    }

    /**
     * 운영자 승인 전이(PENDING → SALE·Track 50). 전이 합법성은 {@link ProductStatus#canTransitionTo}로 가드하며 위반 시
     * {@link IllegalStateException}을 던진다(Service가 {@code ProductInvalidStateException}(422)으로 흡수·직접 매핑 금지).
     * setter 없이 본 mutator 내부에서만 status를 변경한다(캡슐화 유지).
     *
     * @throws IllegalStateException 현재 상태에서 SALE 전이가 불가한 경우(PENDING 아님)
     */
    public void approve() {
        if (!status.canTransitionTo(ProductStatus.SALE)) {
            throw new IllegalStateException("불법 상품 상태 전이: " + status + " → " + ProductStatus.SALE);
        }
        this.status = ProductStatus.SALE;
    }

    /**
     * 운영자 거부 전이(PENDING → REJECTED·종료 상태·재심사 없음·Track 50). 전이 합법성은 {@link ProductStatus#canTransitionTo}로
     * 가드하며 위반 시 {@link IllegalStateException}을 던진다(Service가 {@code ProductInvalidStateException}(422)으로 흡수).
     *
     * @throws IllegalStateException 현재 상태에서 REJECTED 전이가 불가한 경우(PENDING 아님)
     */
    public void reject() {
        if (!status.canTransitionTo(ProductStatus.REJECTED)) {
            throw new IllegalStateException("불법 상품 상태 전이: " + status + " → " + ProductStatus.REJECTED);
        }
        this.status = ProductStatus.REJECTED;
    }

    /**
     * 운영자 판매중지 전이(SALE → STOPPED·Track 71). 전이 합법성은 {@link ProductStatus#canTransitionTo}로 가드하며 위반 시
     * {@link IllegalStateException}을 던진다(Service가 {@code ProductInvalidStateException}(422)으로 흡수). 같은 상태 재요청도
     * 전이 불가로 거부한다(승인의 멱등 no-op과 달리 운영자 오조작 감지 목적).
     *
     * @throws IllegalStateException 현재 상태에서 STOPPED 전이가 불가한 경우(SALE 아님)
     */
    public void stopSale() {
        if (!status.canTransitionTo(ProductStatus.STOPPED)) {
            throw new IllegalStateException("불법 상품 상태 전이: " + status + " → " + ProductStatus.STOPPED);
        }
        this.status = ProductStatus.STOPPED;
    }

    /**
     * 운영자 재판매 전이(STOPPED → SALE·Track 71). 가드·예외 흡수 규칙은 {@link #stopSale()}과 동일하다.
     *
     * @throws IllegalStateException 현재 상태에서 SALE 전이가 불가한 경우(STOPPED 아님·PENDING은 {@link #approve()} 경로)
     */
    public void resumeSale() {
        if (status != ProductStatus.STOPPED || !status.canTransitionTo(ProductStatus.SALE)) {
            throw new IllegalStateException("불법 상품 상태 전이: " + status + " → " + ProductStatus.SALE);
        }
        this.status = ProductStatus.SALE;
    }

    @Override
    protected String getPublicIdPrefix() {
        return "prd";
    }
}
