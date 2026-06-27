package com.zslab.mall.product.repository;

import com.zslab.mall.product.entity.ProductVariant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 상품 변형 Repository(Track 4 read-only·D-59). id·public_id 기준 조회만 제공한다(쓰기는 Track 7 이연).
 */
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    Optional<ProductVariant> findByPublicId(String publicId);

    /** 여러 변형을 일괄 조회한다(주문 품목 enrich·재검증 배치·N+1 회피). */
    List<ProductVariant> findByIdIn(Collection<Long> ids);

    /** 여러 변형을 public_id로 일괄 조회한다(체크아웃 생성 시 가격 도출·D-64). */
    List<ProductVariant> findByPublicIdIn(Collection<String> publicIds);
}
