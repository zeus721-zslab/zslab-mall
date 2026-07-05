package com.zslab.mall.product.repository;

import com.zslab.mall.product.entity.ProductImage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findByProductId(Long productId);

    /**
     * 소유권 스코프 단건 조회(Track 59 BL-6·2-hop). image→product→seller_id를 조인해 요청 판매자 소유 이미지만 반환한다.
     * 타 판매자 소유·미존재를 구분 없이 empty로 은닉한다(BOLA 차단·404 매핑은 Service). {@code @SQLRestriction}으로 삭제
     * 이미지·삭제 상품은 자동 제외된다.
     */
    Optional<ProductImage> findByIdAndProduct_IdAndProduct_SellerId(Long id, Long productId, Long sellerId);

    /** 상품의 현재 대표 이미지를 조회한다(Track 59 BL-6·demote-then-set의 강등 대상 탐색·활성 대표는 최대 1건 전제). */
    Optional<ProductImage> findByProductIdAndMainTrue(Long productId);

    /** 상품의 활성 이미지 개수(Track 59 BL-6·add 시 append displayOrder 산정·삭제분은 @SQLRestriction 자동 제외). */
    long countByProductId(Long productId);
}
