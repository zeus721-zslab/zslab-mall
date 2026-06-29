package com.zslab.mall.claim.repository;

import com.zslab.mall.claim.entity.Claim;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 클레임 Repository(JpaRepository 단일·메서드 이름 쿼리 + 활성 존재 검사).
 */
public interface ClaimRepository extends JpaRepository<Claim, Long> {

    Optional<Claim> findByPublicId(String publicId);

    /**
     * 동일 OrderItem에 활성 클레임(REQUESTED·APPROVED)이 존재하는지 판정한다(CLM-5 사전 가드).
     *
     * <p>orderItemId는 {@code :orderItemId} 바인딩이고 status는 enum 상수 비교다(SQL injection 위험 없음).
     * REJECTED·COMPLETED는 비활성이므로 거절·종결 후 재요청이 허용된다(CLM-2 정합).
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM Claim c "
            + "WHERE c.orderItemId = :orderItemId "
            + "AND c.status IN (com.zslab.mall.claim.enums.ClaimStatus.REQUESTED, "
            + "com.zslab.mall.claim.enums.ClaimStatus.APPROVED)")
    boolean existsActiveByOrderItemId(@Param("orderItemId") Long orderItemId);

    /** Buyer 본인 클레임 목록(requested_by 기준·D-54 페이징). */
    Page<Claim> findAllByRequestedBy(Long requestedBy, Pageable pageable);
}
