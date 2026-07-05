package com.zslab.mall.product.repository;

import com.zslab.mall.product.entity.Product;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 상품 Repository(Track 4 read-only·D-59·Track 44 구매자 카탈로그 조회 추가). id·public_id 기준 조회 + 구매자 카탈로그
 * 노출 목록 조회를 제공한다(쓰기·상태 전이는 Track 7 이연).
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByPublicId(String publicId);

    /**
     * 전이 대상 상품을 비관적 쓰기 락(SELECT ... FOR UPDATE)으로 조회한다(Track 50 승인 워크플로). 운영자 승인·거부 전이의
     * 동시 실행을 행 단위로 직렬화해 이중 전이를 차단한다({@code SettlementRepository.findByIdForUpdate}·D-101 house pattern
     * 준용). 승인 진입키가 public_id이므로 Settlement의 단일 락 쿼리 형태를 public_id 기준으로 준용한다(조회→재락 2쿼리 회피).
     * {@code @SQLRestriction(deleted_at IS NULL)}이 자동 적용돼 삭제 상품은 제외된다. 모든 변수는 :publicId 바인딩이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.publicId = :publicId")
    Optional<Product> findByPublicIdForUpdate(@Param("publicId") String publicId);

    /** 여러 상품을 일괄 조회한다(주문 품목 enrich·재검증 배치·N+1 회피). */
    List<Product> findByIdIn(Collection<Long> ids);

    /** 여러 상품을 public_id로 일괄 조회한다(체크아웃 생성 시 가격·sellerId 도출·D-64). */
    List<Product> findByPublicIdIn(Collection<String> publicIds);

    /**
     * 구매자 카탈로그 노출대상 상품을 페이징 조회한다(Track 44·D1). 노출대상 = Product.status=SALE ∧ Seller.status=ACTIVE이며,
     * deleted_at 제외는 {@code @SQLRestriction}이 Product·Seller·ProductVariant 전 엔티티에 자동 적용한다(status만 명시).
     * categoryId가 null이면 전체, 값이 있으면 해당 카테고리로 필터한다.
     *
     * <p>정렬(sort)은 요청 파라미터(LATEST·PRICE_ASC·PRICE_DESC·NAME)로 분기한다. PRICE는 대표가(basePrice + 판매가능
     * variant의 MIN(additional_price))로 정렬하며, 판매가능 variant가 없으면 COALESCE 0으로 basePrice만 반영한다.
     * LATEST(created_at DESC)는 기본이자 동순위 tiebreaker다. 모든 변수는 :categoryId·:sort 바인딩이다(SQL injection 위험 없음).
     */
    @Query(value = "SELECT p FROM Product p, com.zslab.mall.seller.entity.Seller s "
            + "WHERE p.sellerId = s.id "
            + "AND p.status = com.zslab.mall.product.enums.ProductStatus.SALE "
            + "AND s.status = com.zslab.mall.seller.enums.SellerStatus.ACTIVE "
            + "AND (:categoryId IS NULL OR p.categoryId = :categoryId) "
            + "ORDER BY "
            + "CASE WHEN :sort = 'PRICE_ASC' THEN p.basePrice + "
            + "(SELECT COALESCE(MIN(v.additionalPrice), 0) FROM ProductVariant v "
            + "WHERE v.productId = p.id AND v.status = com.zslab.mall.product.enums.ProductVariantStatus.SALE) END ASC, "
            + "CASE WHEN :sort = 'PRICE_DESC' THEN p.basePrice + "
            + "(SELECT COALESCE(MIN(v.additionalPrice), 0) FROM ProductVariant v "
            + "WHERE v.productId = p.id AND v.status = com.zslab.mall.product.enums.ProductVariantStatus.SALE) END DESC, "
            + "CASE WHEN :sort = 'NAME' THEN p.name END ASC, "
            + "p.createdAt DESC, p.id DESC",
            countQuery = "SELECT COUNT(p) FROM Product p, com.zslab.mall.seller.entity.Seller s "
            + "WHERE p.sellerId = s.id "
            + "AND p.status = com.zslab.mall.product.enums.ProductStatus.SALE "
            + "AND s.status = com.zslab.mall.seller.enums.SellerStatus.ACTIVE "
            + "AND (:categoryId IS NULL OR p.categoryId = :categoryId)")
    Page<Product> findDisplayable(
            @Param("categoryId") Long categoryId, @Param("sort") String sort, Pageable pageable);
}
