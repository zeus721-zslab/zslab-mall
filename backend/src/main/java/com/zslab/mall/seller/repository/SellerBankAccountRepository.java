package com.zslab.mall.seller.repository;

import com.zslab.mall.seller.entity.SellerBankAccount;
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
     * Settlement.bankAccountId 스냅샷 소스다. SLR-3상 최대 1건이나, 데이터 위반 방어 위해 List로 받아 호출부가 첫 건을 취한다.
     *
     * <p>모든 변수는 :sellerId 바인딩만 사용하며 SQL injection 위험이 없다. {@code ba.seller.id}는 @ManyToOne(Seller) FK 경로다.
     */
    @Query("SELECT ba.id FROM SellerBankAccount ba "
            + "WHERE ba.seller.id = :sellerId AND ba.isPrimary = true ORDER BY ba.id")
    List<Long> findPrimaryBankAccountIds(@Param("sellerId") Long sellerId);
}
