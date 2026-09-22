package com.zslab.mall.delivery.adapter;

import com.zslab.mall.delivery.enums.DeliveryCarrier;
import java.time.LocalDateTime;

/**
 * 택배 배송 조회 추상화(Track 99 D-210). 택배사·송장번호만 받으며 주문·클레임 등 도메인 타입에 의존하지 않는다 —
 * {@code deliveryTracker.track(carrier, trackingNo, shippedAt)} 한 줄로 현재 배송 상태를 묻는다.
 *
 * <p>{@link com.zslab.mall.notification.adapter.SmsSender} 계약과 같은 자리의 외부 연동 seam이며, 실 택배사 API 어댑터 도입 시
 * {@link MockDeliveryTracker}만 교체하고 본 계약은 유지한다(D-209 선택 장치 패턴·{@code zslab.delivery.tracker}).
 *
 * <p><b>배달 시각을 돌려주지 않는다(D-210 결정 1-α)</b>: 배달 완료 여부만 계약에 둔다. 실제 {@code delivery.delivered_at}은
 * 조회 결과를 반영한 <b>처리 시각</b>으로 기록하며({@code DeliveryService.markDelivered(now)}), 조회가 알려 주는 배달 시각으로 바꾸는 것은
 * 실 어댑터 도입 시 재검토 대상이다(전환 가이드 배송 조회 절). 처리 시각을 쓰는 이유는 반품 기한·자동 구매확정의 기산점
 * ({@code ReturnWindowPolicy})이 이미 그 값을 쓰고 있어, 조회 시각으로 바꾸면 기존 건의 기한이 소급해 달라지기 때문이다.
 *
 * @see MockDeliveryTracker
 */
public interface DeliveryTracker {

    /**
     * 송장 1건의 현재 배송 상태를 조회한다.
     *
     * @param carrier    택배사
     * @param trackingNo 운송장번호(공백 아님)
     * @param shippedAt  발송 시각(조회 기준 — Mock 구현이 경과 일수를 재는 데 쓴다)
     * @return 조회 결과. 외부 장애·미등록 송장은 {@link DeliveryTrackingStatus#UNKNOWN}으로 돌려주며 예외를 던지지 않는다
     *         (한 건의 조회 실패가 배치를 멈추지 않게 한다)
     */
    DeliveryTrackingStatus track(DeliveryCarrier carrier, String trackingNo, LocalDateTime shippedAt);
}
