package com.zslab.mall.seller.repository;

import com.zslab.mall.seller.entity.Seller;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 판매자 Repository(Track 4 read-only·D-59). id·public_id 기준 조회만 제공한다(쓰기는 Track 7 이연).
 */
public interface SellerRepository extends JpaRepository<Seller, Long> {

    Optional<Seller> findByPublicId(String publicId);

    /** 여러 판매자를 일괄 조회한다(seller 그룹화 응답 enrich·N+1 회피·§11). */
    List<Seller> findByIdIn(Collection<Long> ids);
}
