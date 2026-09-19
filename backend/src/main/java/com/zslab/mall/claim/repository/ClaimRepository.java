package com.zslab.mall.claim.repository;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimInspectionResult;
import com.zslab.mall.claim.enums.ClaimType;
import java.time.LocalDateTime;
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

    /**
     * 환불 누락 클레임(D-172·RefundRecoveryScheduler). 환불이 시작됐어야 하는데 Refund 행이 하나도 없는 클레임 — CANCEL은 승인(processedAt),
     * RETURN은 검수 PASS(inspectedAt) 후 {@code threshold} 이전. FAILED 행이 있으면 "환불 없음"이 아니라 관리자 재시도 경로라 자연 제외된다.
     * 모든 변수는 :name 바인딩 사용, SQL injection 위험 없음.
     */
    @Query("SELECT c.id FROM Claim c WHERE c.status = com.zslab.mall.claim.enums.ClaimStatus.APPROVED "
            + "AND NOT EXISTS (SELECT 1 FROM Refund r WHERE r.claimId = c.id) "
            + "AND ((c.type = com.zslab.mall.claim.enums.ClaimType.CANCEL AND c.processedAt <= :threshold) "
            + "  OR (c.type = com.zslab.mall.claim.enums.ClaimType.RETURN "
            + "      AND c.inspectionResult = com.zslab.mall.claim.enums.ClaimInspectionResult.PASS AND c.inspectedAt <= :threshold)) "
            + "ORDER BY c.id ASC")
    List<Long> findRefundMissingClaimIds(@Param("threshold") LocalDateTime threshold, Pageable pageable);

    /** Buyer 본인 클레임 목록(requested_by 기준·D-54 페이징). */
    Page<Claim> findAllByRequestedBy(Long requestedBy, Pageable pageable);

    /** 관리자 주문 목록·상세 배치 enrich(Track 79 D-168·N+1 회피). 항목별 최신 행이 앞에 오도록 id 내림차순. */
    List<Claim> findByOrderItemIdInOrderByIdDesc(Collection<Long> orderItemIds);

    /** 동일 품목에 검수 불합격(FAIL) 이력이 있는지(Track 81-A D-170 보충·반품 재요청 차단). 파생 쿼리 바인딩. */
    boolean existsByOrderItemIdAndTypeAndInspectionResult(Long orderItemId, ClaimType type, ClaimInspectionResult inspectionResult);

    /** 동일 품목에 유형 무관 검수 불합격(FAIL) 이력이 있는지(Track 83 D-177·반품·교환 재요청 차단). 파생 쿼리 바인딩. */
    boolean existsByOrderItemIdAndInspectionResult(Long orderItemId, ClaimInspectionResult inspectionResult);

    /** 동일 품목에 특정 유형·상태 클레임이 있는지(Track 83 D-177 결정 11·EXCHANGE COMPLETED = 재교환 차단). 파생 쿼리 바인딩. */
    boolean existsByOrderItemIdAndTypeAndStatus(Long orderItemId, ClaimType type, ClaimStatus status);

    /** 관리자 목록 처리 대기 건수(Track 80 D-169·유형 전체). */
    long countByStatus(ClaimStatus status);

    /** 관리자 목록 처리 대기 건수(Track 80 D-169·유형 탭 반영). */
    long countByTypeAndStatus(ClaimType type, ClaimStatus status);

    /**
     * 구매자에게 활성(REQUESTED·APPROVED) 클레임이 있는지(Track 84 탈퇴 가드). claim → order_item → order.buyer_id 경로이며
     * requested_by는 쓰지 않는다(관리자 취소 생성분 포함). 활성 기준은 {@link #existsActiveByOrderItemId}와 동일하다.
     *
     * <p>buyerId는 {@code :buyerId} 바인딩이고 status는 enum 상수 비교다(SQL injection 위험 없음).
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM Claim c, OrderItem oi "
            + "WHERE oi.id = c.orderItemId AND oi.order.buyerId = :buyerId "
            + "AND c.status IN (com.zslab.mall.claim.enums.ClaimStatus.REQUESTED, "
            + "com.zslab.mall.claim.enums.ClaimStatus.APPROVED)")
    boolean existsActiveByBuyerId(@Param("buyerId") Long buyerId);

    /**
     * 셀러 품목에 걸린 활성 클레임(REQUESTED·APPROVED) 수(Track 89-D 종료 가드 G3·D-187). 활성 기준은
     * {@link #existsActiveByOrderItemId}와 동일하며 claim → order_item.seller_id 경로로 센다. 모든 변수는 :sellerId 바인딩이다.
     */
    @Query("SELECT COUNT(c) FROM Claim c, OrderItem oi "
            + "WHERE oi.id = c.orderItemId AND oi.sellerId = :sellerId "
            + "AND c.status IN (com.zslab.mall.claim.enums.ClaimStatus.REQUESTED, "
            + "com.zslab.mall.claim.enums.ClaimStatus.APPROVED)")
    long countActiveBySellerId(@Param("sellerId") Long sellerId);
}
