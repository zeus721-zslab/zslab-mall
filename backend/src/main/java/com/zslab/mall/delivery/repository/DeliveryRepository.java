package com.zslab.mall.delivery.repository;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * 품목의 원 주문 발송(OUTBOUND·claim_id NULL) 최신 배송완료 행(Track 81-A·반품 기한·자동 구매확정 기준 시각). 교환품 발송·검수 불합격
     * 재발송(claim_id NOT NULL)은 기준에서 제외한다. 모든 변수는 메서드 이름 쿼리 바인딩이다(SQL injection 위험 없음).
     */
    Optional<Delivery> findFirstByOrderItemIdAndDirectionAndStatusAndClaimIdIsNullOrderByDeliveredAtDesc(
            Long orderItemId, DeliveryDirection direction, DeliveryStatus status);

    /** 관리자 목록·상세 배치 enrich(Track 81-A·클레임별 회수/재발송 Delivery). id 내림차순. */
    List<Delivery> findByClaimIdInOrderByIdDesc(Collection<Long> claimIds);

    /** 관리자 주문 목록·상세 배치 enrich(Track 79 D-168·N+1 회피). 항목별 최신 행이 앞에 오도록 id 내림차순. */
    List<Delivery> findByOrderItemIdInOrderByIdDesc(Collection<Long> orderItemIds);

    /** 관리자 주문 목록·상세 배치 enrich(Track 81-A·발송 Delivery만 — 반품 회수 RETURN은 품목 배송 상태가 아니다). id 내림차순. */
    List<Delivery> findByOrderItemIdInAndDirectionOrderByIdDesc(Collection<Long> orderItemIds, DeliveryDirection direction);
}
