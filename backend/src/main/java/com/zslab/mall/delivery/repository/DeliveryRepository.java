package com.zslab.mall.delivery.repository;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.order.enums.OrderItemStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 배송 Repository.
 */
public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByPublicId(String publicId);

    /** 교환 배송 이중 등록 멱등 가드용(D-99 Q11). claim_id 연결된 Delivery 존재 시 재등록을 차단한다. */
    Optional<Delivery> findByClaimId(Long claimId);

    /** 클레임에 연결된 특정 방향 Delivery(Track 81-A·반품 회수 RETURN / 재발송·교환 OUTBOUND). 한 클레임에 방향별 최대 1건 운영. */
    Optional<Delivery> findByClaimIdAndDirection(Long claimId, DeliveryDirection direction);

    /**
     * 품목의 기준 발송 최신 배송완료 행(Track 81-A·반품 기한·자동 구매확정 기준 시각 / Track 83 D-177 결정 6). 원 주문 발송(claim_id NULL)과
     * 교환품 발송(EXCHANGE 클레임 연결)을 포함하고 검수 불합격 재발송(RETURN 클레임 연결)은 제외한다. 모든 변수는 :name 바인딩 사용,
     * SQL injection 위험 없음.
     */
    @Query("SELECT d FROM Delivery d LEFT JOIN Claim c ON c.id = d.claimId "
            + "WHERE d.orderItemId = :orderItemId AND d.direction = :direction AND d.status = :status "
            + "AND (d.claimId IS NULL OR c.type = com.zslab.mall.claim.enums.ClaimType.EXCHANGE) "
            + "ORDER BY d.deliveredAt DESC, d.id DESC")
    List<Delivery> findBaseDeliveredOutbound(
            @Param("orderItemId") Long orderItemId,
            @Param("direction") DeliveryDirection direction,
            @Param("status") DeliveryStatus status,
            Pageable pageable);

    /**
     * 자동 구매확정 후보 품목 id(Track 81-B D-171). 원 주문 발송(OUTBOUND·claim_id NULL) 배송완료가 {@code threshold} 이전이고 품목이
     * 아직 DELIVERED인 행 — V24 인덱스 (direction, status, claim_id, delivered_at) 범위 스캔 후 order_item 조인. 확정 여부의 최종 판정은
     * 서비스가 행 락 후 {@code ReturnWindowPolicy}로 재확인한다. 모든 변수는 :name 바인딩 사용, SQL injection 위험 없음.
     */
    @Query("SELECT DISTINCT d.orderItemId FROM Delivery d JOIN OrderItem oi ON oi.id = d.orderItemId "
            + "LEFT JOIN Claim c ON c.id = d.claimId "
            + "WHERE d.direction = :direction AND d.status = :status "
            + "AND (d.claimId IS NULL OR c.type = com.zslab.mall.claim.enums.ClaimType.EXCHANGE) "
            + "AND d.deliveredAt <= :threshold AND oi.itemStatus = :itemStatus ORDER BY d.orderItemId ASC")
    List<Long> findAutoConfirmCandidateOrderItemIds(
            @Param("direction") DeliveryDirection direction,
            @Param("status") DeliveryStatus status,
            @Param("threshold") LocalDateTime threshold,
            @Param("itemStatus") OrderItemStatus itemStatus,
            Pageable pageable);

    /** 관리자 목록·상세 배치 enrich(Track 81-A·클레임별 회수/재발송 Delivery). id 내림차순. */
    List<Delivery> findByClaimIdInOrderByIdDesc(Collection<Long> claimIds);

    /** 관리자 주문 목록·상세 배치 enrich(Track 79 D-168·N+1 회피). 항목별 최신 행이 앞에 오도록 id 내림차순. */
    List<Delivery> findByOrderItemIdInOrderByIdDesc(Collection<Long> orderItemIds);

    /** 관리자 주문 목록·상세 배치 enrich(Track 81-A·발송 Delivery만 — 반품 회수 RETURN은 품목 배송 상태가 아니다). id 내림차순. */
    List<Delivery> findByOrderItemIdInAndDirectionOrderByIdDesc(Collection<Long> orderItemIds, DeliveryDirection direction);
}
