package com.zslab.mall.seller.repository;

import com.zslab.mall.seller.entity.SellerBankAccount;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /** 셀러의 계좌 전부(등록순·Track 89-F 상세 목록). 모든 변수는 :sellerId 바인딩이다. */
    @Query("SELECT ba FROM SellerBankAccount ba WHERE ba.seller.id = :sellerId ORDER BY ba.id")
    List<SellerBankAccount> findAllBySellerId(@Param("sellerId") Long sellerId);

    /** 셀러 소속 계좌 1건(타 셀러 계좌는 empty → 404 존재 은닉·Track 89-F). 모든 변수는 바인딩이다. */
    @Query("SELECT ba FROM SellerBankAccount ba WHERE ba.id = :id AND ba.seller.id = :sellerId")
    Optional<SellerBankAccount> findByIdAndSellerId(@Param("id") Long id, @Param("sellerId") Long sellerId);

    /** 셀러에 계좌가 하나라도 있는지(첫 계좌 자동 주 계좌 판정·Track 89-F). */
    @Query("SELECT COUNT(ba) > 0 FROM SellerBankAccount ba WHERE ba.seller.id = :sellerId")
    boolean existsBySellerId(@Param("sellerId") Long sellerId);

    /**
     * 셀러의 현재 주 계좌를 전부 해제한다(주 계좌 전환 1단계·Track 89-F·D-188). 벌크 JPQL은 즉시 실행되므로 이어지는 엔티티
     * {@code markPrimary()} flush보다 반드시 먼저 DB에 반영된다 — 같은 영속성 컨텍스트에서 두 엔티티를 바꾸면 flush 순서가 보장되지
     * 않아 promote가 먼저 나가면 {@code uk_seller_bank_account_primary} 위반이 난다(정찰 §6-2 트랩). {@code flushAutomatically}로
     * 선행 변경을 먼저 내보내고, {@code clearAutomatically}는 두지 않는다(호출부가 방금 로드한 대상 엔티티가 detach되면 promote가 유실된다).
     * updated_at은 벌크 UPDATE가 JPA Auditing을 타지 않으므로 파라미터로 직접 기록한다(updated_by는 AuditorAware 정책상 전 엔티티 NULL·동일).
     * 모든 변수는 바인딩만 사용하며 SQL injection 위험이 없다.
     *
     * @return 해제된 행 수(SLR-3상 0 또는 1)
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE SellerBankAccount ba SET ba.isPrimary = false, ba.updatedAt = :now "
            + "WHERE ba.seller.id = :sellerId AND ba.isPrimary = true")
    int demotePrimary(@Param("sellerId") Long sellerId, @Param("now") LocalDateTime now);
}
