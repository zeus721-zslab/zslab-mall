package com.zslab.mall.seller.repository;

import com.zslab.mall.seller.entity.Seller;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 판매자 Repository(Track 4·D-59). Track 89-D에서 관리자 목록(Specification)·전이용 비관적 락·사업자번호 중복 선검사를 추가했다.
 */
public interface SellerRepository extends JpaRepository<Seller, Long>, JpaSpecificationExecutor<Seller> {

    Optional<Seller> findByPublicId(String publicId);

    /**
     * 상태 전이·정보 수정용 비관적 락 조회(Track 89-D·{@code ProductRepository.findByPublicIdForUpdate} 선례). 같은 셀러에 대한
     * 동시 전이 요청을 직렬화해 canTransitionTo 판정이 최신 상태로 이뤄지게 한다. 모든 변수는 :publicId 바인딩이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seller s WHERE s.publicId = :publicId")
    Optional<Seller> findByPublicIdForUpdate(@Param("publicId") String publicId);

    /** 사업자번호 중복 선검사(Track 89-D·uk_seller_business_no·soft-delete 행은 @SQLRestriction으로 제외되나 UK는 남아 있음). */
    boolean existsByBusinessNo(String businessNo);

    /** 여러 판매자를 일괄 조회한다(seller 그룹화 응답 enrich·N+1 회피·§11). */
    List<Seller> findByIdIn(Collection<Long> ids);

    /**
     * 상호 부분일치 판매자 id(Track 85 정산 목록 keyword). 모든 변수는 :pattern 바인딩(호출부가 LIKE 패턴 조립·%·_·\\ 이스케이프)이며
     * ESCAPE '\\'로 리터럴 매칭을 보장한다(ProductRepository 선례).
     */
    @Query("SELECT s.id FROM Seller s WHERE s.companyName LIKE :pattern ESCAPE '\\'")
    List<Long> findIdsByCompanyNameLike(@Param("pattern") String pattern);
}
