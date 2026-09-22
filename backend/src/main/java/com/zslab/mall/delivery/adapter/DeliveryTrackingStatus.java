package com.zslab.mall.delivery.adapter;

/**
 * 배송 조회 결과(Track 99 D-210). 택배사마다 다른 세부 단계(집화·간선·배달출발 …)를 자동 배송완료 판정에 필요한 3값으로만 좁힌다 —
 * 세부 단계는 소비처가 없다(과잉개발 회피).
 *
 * <p>{@link #DELIVERED}만 자동 전이 대상이며 나머지 둘은 다음 실행에서 다시 조회한다.
 */
public enum DeliveryTrackingStatus {

    /** 배달 완료. 자동 배송완료 전이 대상이다. */
    DELIVERED,

    /** 아직 이동 중(집화·간선·배달 출발 등). 다음 실행에서 다시 조회한다. */
    IN_TRANSIT,

    /** 조회 불가(미등록 송장·외부 장애·응답 해석 실패). 전이하지 않고 다음 실행에서 다시 조회한다. */
    UNKNOWN
}
