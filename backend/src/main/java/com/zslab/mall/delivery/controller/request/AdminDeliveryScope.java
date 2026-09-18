package com.zslab.mall.delivery.controller.request;

/**
 * 관리자 배송 목록 조회 범위(Track 89-B D-184 §1-B). direction·claim 연계 2축 대신 단일 축으로 둔다 — RETURN은 항상 클레임 연계
 * (회수 송장 등록 경로만 생성)라 claimLinked는 OUTBOUND 안에서만 의미가 있고, 2축이면 {@code RETURN ∧ claimLinked=false} 같은
 * 항상-빈 조합이 생기기 때문이다. 실데이터 조합(원 발송·교환품 발송·반품 회수·교환 회수)과 1:1이며 기본값 ORIGINAL은 교환품 발송을
 * 포함하지 않는다. 조회 파라미터라 4층위 enum 잠금 대상은 아니다. 허용 외 값은 Spring 변환 실패 400.
 */
public enum AdminDeliveryScope {
    /** 원 주문 발송(OUTBOUND ∧ claim_id NULL). */
    ORIGINAL,
    /** 클레임 연계 발송(OUTBOUND ∧ claim_id NOT NULL·교환품 발송·검수 불합격 재발송). */
    CLAIM_OUTBOUND,
    /** 반품·교환 회수(RETURN·항상 claim 연계). */
    RETURN,
    ALL
}
