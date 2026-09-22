package com.zslab.mall.delivery.adapter;

import com.zslab.mall.delivery.enums.DeliveryCarrier;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock 배송 조회 어댑터(Track 99 D-210). 택배사 API를 부르지 않고 <b>발송 후 경과 일수</b>로 배달 완료를 모사한다 —
 * {@code shipped_at + zslab.delivery.tracker.mock-days} 이 지났으면 {@link DeliveryTrackingStatus#DELIVERED}, 아니면
 * {@link DeliveryTrackingStatus#IN_TRANSIT}다. 송장번호가 없으면 조회할 것이 없으므로 {@link DeliveryTrackingStatus#UNKNOWN}이다.
 *
 * <p>활성 조건은 {@code zslab.delivery.tracker=mock}(미지정 시 mock·D-209 선택 장치 패턴)이다. 실 택배사 어댑터 도입 시
 * 본 구현만 교체한다(계약 {@link DeliveryTracker}는 유지).
 *
 * <p><b>경과 일수 키가 {@code zslab.delivery.tracker-mock-days}인 이유</b>: {@code zslab.delivery.tracker}가 이미 스칼라 값이라
 * 그 아래에 {@code .mock-days} 하위 키를 둘 수 없다(YAML 키 충돌). 같은 트리에 남기되 형제 키로 뗀다.
 *
 * <p><b>이 구현이 "시간 기반"인 것과 스케줄러가 "조회 결과 기반"인 것은 다른 층이다</b>: 스케줄러는 조회 결과만 보고 전이하며,
 * 경과 일수를 아는 것은 이 어댑터뿐이다. 실 어댑터로 바꾸면 스케줄러는 그대로 두고 판정 근거만 실제 배송 상태로 바뀐다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zslab.delivery.tracker", havingValue = "mock", matchIfMissing = true)
public class MockDeliveryTracker implements DeliveryTracker {

    private final int mockDays;

    public MockDeliveryTracker(@Value("${zslab.delivery.tracker-mock-days:2}") int mockDays) {
        if (mockDays < 0) {
            throw new IllegalArgumentException("zslab.delivery.tracker-mock-days는 0 이상이어야 합니다: " + mockDays);
        }
        this.mockDays = mockDays;
    }

    @Override
    public DeliveryTrackingStatus track(DeliveryCarrier carrier, String trackingNo, LocalDateTime shippedAt) {
        if (trackingNo == null || trackingNo.isBlank() || shippedAt == null) {
            log.debug("[MockDeliveryTracker] 조회 불가(송장·발송 시각 없음): carrier={} trackingNo={}", carrier, trackingNo);
            return DeliveryTrackingStatus.UNKNOWN;
        }
        boolean delivered = !LocalDateTime.now().isBefore(shippedAt.plusDays(mockDays));
        return delivered ? DeliveryTrackingStatus.DELIVERED : DeliveryTrackingStatus.IN_TRANSIT;
    }
}
