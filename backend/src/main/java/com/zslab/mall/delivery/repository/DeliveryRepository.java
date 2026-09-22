package com.zslab.mall.delivery.repository;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.order.enums.OrderItemStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 배송 Repository. {@link JpaSpecificationExecutor}는 관리자 배송 목록(Track 89-B·{@code AdminDeliverySpecifications}) 전용이다.
 */
public interface DeliveryRepository extends JpaRepository<Delivery, Long>, JpaSpecificationExecutor<Delivery> {

    Optional<Delivery> findByPublicId(String publicId);

    /**
     * 행 락을 잡고 읽는다(Track 99 외부 검토 4·lost update 차단). {@link Delivery}에는 {@code @Version}도 {@code @DynamicUpdate}도 없어
     * 더티 체킹 UPDATE가 <b>전 컬럼</b>을 쓴다 — 락 없이 읽은 트랜잭션이 나중에 저장하면 그 사이 다른 트랜잭션이 커밋한 status·delivered_at까지
     * 자기가 읽은 옛 값으로 되돌린다(송장 정정이 배송완료를 지운다). 전이(배송완료)·값 보정(송장 정정) 경로는 <b>해당 트랜잭션에서 이 행을 처음
     * 읽을 때</b> 이 메서드를 써야 한다 — 먼저 락 없이 읽어 두면 1차 캐시가 그 인스턴스를 돌려줘 락을 잡고도 옛 상태로 판정한다(D-168 트랩).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Delivery> findWithLockById(Long id);

    /** publicId로 행 락을 잡고 읽는다(송장 정정 경로·{@link #findWithLockById}와 같은 규약). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Delivery> findWithLockByPublicId(String publicId);

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

    /**
     * 자동 배송완료 후보(Track 99 D-210). 발송 방향(OUTBOUND)·배송중(SHIPPING)·송장 보유 행을 id 오름차순으로 한 페이지 돌려준다.
     * {@code cursor}보다 큰 id만 보므로, 배달 완료가 아니어서 남는 건이 다음 페이지를 막지 않는다(굶주림 방지).
     * 교환품 발송·검수 불합격 재발송(claim_id 보유)도 발송이므로 포함하고, 회수(RETURN)는 조건에서 빠진다.
     * 모든 변수는 :name 바인딩 사용, SQL injection 위험 없음.
     */
    @Query("SELECT new com.zslab.mall.delivery.repository.DeliveryTrackingCandidate("
            + "d.id, d.carrier, d.trackingNo, d.shippedAt) FROM Delivery d "
            + "WHERE d.direction = :direction AND d.status = :status AND d.trackingNo IS NOT NULL "
            + "AND d.id > :cursor ORDER BY d.id ASC")
    List<DeliveryTrackingCandidate> findAutoCompleteCandidates(
            @Param("direction") DeliveryDirection direction,
            @Param("status") DeliveryStatus status,
            @Param("cursor") Long cursor,
            Pageable pageable);

    /** 관리자 목록·상세 배치 enrich(Track 81-A·클레임별 회수/재발송 Delivery). id 내림차순. */
    List<Delivery> findByClaimIdInOrderByIdDesc(Collection<Long> claimIds);

    /** 관리자 주문 목록·상세 배치 enrich(Track 79 D-168·N+1 회피). 항목별 최신 행이 앞에 오도록 id 내림차순. */
    List<Delivery> findByOrderItemIdInOrderByIdDesc(Collection<Long> orderItemIds);

    /** 관리자 주문 목록·상세 배치 enrich(Track 81-A·발송 Delivery만 — 반품 회수 RETURN은 품목 배송 상태가 아니다). id 내림차순. */
    List<Delivery> findByOrderItemIdInAndDirectionOrderByIdDesc(Collection<Long> orderItemIds, DeliveryDirection direction);
}
