package com.zslab.mall.claim.repository;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimInspectionResult;
import com.zslab.mall.claim.enums.ClaimType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 클레임 Repository(JpaRepository + 관리자 목록 Specification·메서드 이름 쿼리 + 활성 존재 검사).
 */
public interface ClaimRepository extends JpaRepository<Claim, Long>, JpaSpecificationExecutor<Claim> {

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

    /** 관리자 주문 목록·상세 배치 enrich(Track 79 D-168·N+1 회피). 항목별 최신 행이 앞에 오도록 id 내림차순. */
    List<Claim> findByOrderItemIdInOrderByIdDesc(Collection<Long> orderItemIds);

    /** 동일 품목에 검수 불합격(FAIL) 이력이 있는지(Track 81-A D-170 보충·반품 재요청 차단). 파생 쿼리 바인딩. */
    boolean existsByOrderItemIdAndTypeAndInspectionResult(Long orderItemId, ClaimType type, ClaimInspectionResult inspectionResult);

    /** 관리자 목록 처리 대기 건수(Track 80 D-169·유형 전체). */
    long countByStatus(ClaimStatus status);

    /** 관리자 목록 처리 대기 건수(Track 80 D-169·유형 탭 반영). */
    long countByTypeAndStatus(ClaimType type, ClaimStatus status);
}
