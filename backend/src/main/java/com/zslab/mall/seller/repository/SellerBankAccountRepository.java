package com.zslab.mall.seller.repository;

import com.zslab.mall.seller.entity.SellerBankAccount;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 판매자 정산계좌 Repository.
 */
public interface SellerBankAccountRepository extends JpaRepository<SellerBankAccount, Long> {

    /**
     * 판매자의 주 정산계좌(is_primary=true) id를 조회한다(Track 48 P3·SLR-3 "is_primary 단일"이 정산 대상 계좌 결정성).
     * Settlement.bankAccountId 스냅샷 소스다. SLR-3상 최대 1건이나 DB에 is_primary UNIQUE 제약이 없어(V1·§8 이월) 데이터 위반 방어 위해
     * List로 받아 호출부가 첫 건을 취한다. {@code ORDER BY ba.id} 오름차순이므로 2건 이상이어도 선택은 결정적(가장 먼저 등록된 계좌)이다.
     *
     * <p>모든 변수는 :sellerId 바인딩만 사용하며 SQL injection 위험이 없다. {@code ba.seller.id}는 @ManyToOne(Seller) FK 경로다.
     */
    @Query("SELECT ba.id FROM SellerBankAccount ba "
            + "WHERE ba.seller.id = :sellerId AND ba.isPrimary = true ORDER BY ba.id")
    List<Long> findPrimaryBankAccountIds(@Param("sellerId") Long sellerId);

    /** 주 정산계좌 보유 seller id 집합(Track 85 목록 enrich·N+1 회피). 모든 변수는 :sellerIds 바인딩이다. */
    @Query("SELECT DISTINCT ba.seller.id FROM SellerBankAccount ba WHERE ba.seller.id IN :sellerIds AND ba.isPrimary = true")
    List<Long> findSellerIdsHavingPrimary(@Param("sellerIds") Collection<Long> sellerIds);

    /** 셀러의 현재 주 정산계좌(SLR-3상 최대 1건·방어적으로 첫 건). 모든 변수는 :sellerId 바인딩이다. */
    @Query("SELECT ba FROM SellerBankAccount ba WHERE ba.seller.id = :sellerId AND ba.isPrimary = true ORDER BY ba.id")
    List<SellerBankAccount> findPrimaryBankAccounts(@Param("sellerId") Long sellerId);
}
