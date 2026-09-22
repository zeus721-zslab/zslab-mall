package com.zslab.mall.delivery.repository;

import com.zslab.mall.delivery.enums.DeliveryCarrier;
import java.time.LocalDateTime;

/**
 * 자동 배송완료 후보 1건(Track 99 D-210). 배송 조회에 필요한 필드만 담는다 — 조회는 트랜잭션 밖에서 하므로 영속 엔티티를
 * 들고 나가지 않는다(detached 엔티티 오사용 차단).
 *
 * @param deliveryId 배송 id(전이 대상·커서 값)
 * @param carrier    택배사
 * @param trackingNo 운송장번호
 * @param shippedAt  발송 시각
 */
public record DeliveryTrackingCandidate(Long deliveryId, DeliveryCarrier carrier, String trackingNo,
        LocalDateTime shippedAt) {
}
