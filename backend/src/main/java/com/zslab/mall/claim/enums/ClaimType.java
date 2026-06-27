package com.zslab.mall.claim.enums;

/**
 * 클레임 유형(A분류·A#13·3값). DDL {@code claim.type} ENUM 정합.
 *
 * <p>state-machine.md §2 "Claim.type별 COMPLETED 진입 조건"에서 type별 종결 조건이 갈린다. 본 트랙(Track 5)은
 * {@link #CANCEL}만 Refund.COMPLETED → Claim.COMPLETED 전이를 구동하며 {@link #RETURN}·{@link #EXCHANGE}는
 * 수거/교환출고 등 추가 단계가 필요해 본 트랙 범위 밖이다(expected-spec §3.2).
 */
public enum ClaimType {
    /** 취소: Refund.COMPLETED → Claim.COMPLETED (본 트랙 구동 대상). */
    CANCEL,
    /** 반품: 수거 확인 + Refund.COMPLETED 필요 (본 트랙 미전이). */
    RETURN,
    /** 교환: 수거 확인 + 교환품 발송 완료 필요 (본 트랙 미전이). */
    EXCHANGE
}
