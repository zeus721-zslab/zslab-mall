package com.zslab.mall.inventory.repository;

import com.zslab.mall.product.entity.ProductVariant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 셀러 재고 목록 전용 리포지토리(Track 90-C-1). 행 축은 {@link ProductVariant}(재고는 variant 1:1·INV-6)이며 소유 조건
 * {@code product.seller_id = :sellerId}는 variant에 FK 연관이 없어({@code ProductVariant.productId} Long 컬럼) theta-join으로 건다
 * ({@code ProductRepository.findDisplayable} 선례). {@code SellerDashboardRepository}와 같이 CRUD를 노출하지 않는 {@link Repository}
 * 마커만 상속한다. soft-delete된 variant·product는 양쪽 {@code @SQLRestriction}이 자동 제외한다.
 *
 * <p>정렬은 상품 최신 등록순(product.id DESC) → variant displayOrder → variant id로 고정한다. 네이티브 쿼리를 쓰지 않으며 모든 변수는
 * :바인딩만 사용해 SQL injection 위험이 없다.
 */
public interface SellerInventoryRepository extends Repository<ProductVariant, Long> {

    /**
     * 자기 상품의 variant 페이지. keywordPattern은 상품명 또는 sellerSku 부분일치(escape된 LIKE 패턴·null=조건 없음),
     * productPublicId는 상품 1건 한정(null=조건 없음).
     */
    @Query(value = "SELECT v FROM ProductVariant v, com.zslab.mall.product.entity.Product p "
            + "WHERE v.productId = p.id AND p.sellerId = :sellerId "
            + "AND (:productPublicId IS NULL OR p.publicId = :productPublicId) "
            + "AND (:keywordPattern IS NULL OR p.name LIKE :keywordPattern ESCAPE '\\' "
            + "OR v.sellerSku LIKE :keywordPattern ESCAPE '\\') "
            + "ORDER BY p.id DESC, v.displayOrder ASC, v.id ASC",
            countQuery = "SELECT COUNT(v) FROM ProductVariant v, com.zslab.mall.product.entity.Product p "
            + "WHERE v.productId = p.id AND p.sellerId = :sellerId "
            + "AND (:productPublicId IS NULL OR p.publicId = :productPublicId) "
            + "AND (:keywordPattern IS NULL OR p.name LIKE :keywordPattern ESCAPE '\\' "
            + "OR v.sellerSku LIKE :keywordPattern ESCAPE '\\')")
    Page<ProductVariant> findOwnedVariants(
            @Param("sellerId") Long sellerId,
            @Param("productPublicId") String productPublicId,
            @Param("keywordPattern") String keywordPattern,
            Pageable pageable);
}
