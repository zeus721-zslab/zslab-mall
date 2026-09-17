package com.zslab.mall.delivery.service;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 배송완료 기준 기한 판정 단일 소스(Track 81-A D-170 보충). 반품 요청 기한(R1)과 81-B 자동 구매확정(R7)이 같은 기준 시각·같은 일수를 쓴다.
 *
 * <p><b>기준 시각</b>: 품목의 원 주문 발송(direction=OUTBOUND·claim_id IS NULL·DELIVERED) 최신 {@code delivered_at}. 교환품 발송·검수 불합격
 * 재발송은 claim_id가 있어 제외된다 — 재발송 배송완료로 기한이 되살아나지 않는다(교환 발송 기준 재설정은 Track 82 판단).
 */
@Component
public class ReturnWindowPolicy {

    /** 배송완료 후 반품 가능·자동 구매확정 기준 일수(R1·R7). */
    public static final int WINDOW_DAYS = 7;

    private final DeliveryRepository deliveryRepository;

    public ReturnWindowPolicy(DeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    /**
     * 품목의 기준 발송 배송완료 시각(원 주문 발송 또는 교환품 발송 중 최신·Track 83 D-177 결정 6·검수 FAIL 재발송 제외). 배송완료 기록이
     * 없으면 empty. 교환품 배송완료가 있으면 그 시각부터 반품 기한·자동 구매확정 타이머가 다시 시작된다.
     */
    public Optional<LocalDateTime> originalDeliveredAt(Long orderItemId) {
        return deliveryRepository
                .findBaseDeliveredOutbound(orderItemId, DeliveryDirection.OUTBOUND, DeliveryStatus.DELIVERED, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(Delivery::getDeliveredAt);
    }

    /** 기한 만료 시각(배송완료 + {@value #WINDOW_DAYS}일). */
    public static LocalDateTime deadlineOf(LocalDateTime deliveredAt) {
        return deliveredAt.plusDays(WINDOW_DAYS);
    }

    /** {@code at}이 기한 안인지(만료 시각 포함). */
    public static boolean isWithinWindow(LocalDateTime deliveredAt, LocalDateTime at) {
        return !at.isAfter(deadlineOf(deliveredAt));
    }
}
