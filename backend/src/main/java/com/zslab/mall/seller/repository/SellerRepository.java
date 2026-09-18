package com.zslab.mall.seller.repository;

import com.zslab.mall.seller.entity.Seller;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 판매자 Repository(Track 4 read-only·D-59). id·public_id 기준 조회만 제공한다(쓰기는 Track 7 이연).
 */
public interface SellerRepository extends JpaRepository<Seller, Long> {

    Optional<Seller> findByPublicId(String publicId);

    /** 여러 판매자를 일괄 조회한다(seller 그룹화 응답 enrich·N+1 회피·§11). */
    List<Seller> findByIdIn(Collection<Long> ids);

    /**
     * 상호 부분일치 판매자 id(Track 85 정산 목록 keyword). 모든 변수는 :pattern 바인딩(호출부가 LIKE 패턴 조립·%·_·\\ 이스케이프)이며
     * ESCAPE '\\'로 리터럴 매칭을 보장한다(ProductRepository 선례).
     */
    @Query("SELECT s.id FROM Seller s WHERE s.companyName LIKE :pattern ESCAPE '\\'")
    List<Long> findIdsByCompanyNameLike(@Param("pattern") String pattern);
}
