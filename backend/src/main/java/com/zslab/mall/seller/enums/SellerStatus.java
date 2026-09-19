package com.zslab.mall.seller.enums;

/**
 * 판매자 상태(B분류·SELLER_STATUS·SLR-4·4값). DDL {@code seller.status} ENUM 정합.
 *
 * <p><b>전이 매트릭스(state-machine.md §7·Track 89-D·D-187)</b>: PENDING → ACTIVE(입점 승인)·TERMINATED(승인 거부) /
 * ACTIVE ⇄ SUSPENDED(정지·해제) / ACTIVE·SUSPENDED → TERMINATED(종료). TERMINATED는 불가역 종결 상태다. 같은 상태 재요청도
 * 불가(운영자 오조작 감지·D-160 §1-A 3 관습). 강제는 {@link #canTransitionTo}로 하며 {@code Seller.changeStatus}가 가드로 쓴다
 * ({@code ProductStatus}·{@code SettlementStatus} 동일 패턴).
 */
public enum SellerStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    TERMINATED;

    /**
     * 현재 상태에서 {@code next}로의 전이가 합법인지 판정한다(state-machine.md §7 매트릭스).
     *
     * @param next 목표 상태
     * @return 합법 전이면 true
     */
    public boolean canTransitionTo(SellerStatus next) {
        return switch (this) {
            case PENDING -> next == ACTIVE || next == TERMINATED;
            case ACTIVE -> next == SUSPENDED || next == TERMINATED;
            case SUSPENDED -> next == ACTIVE || next == TERMINATED;
            // 종결 상태(불가역) — 어떤 전이도 불가
            case TERMINATED -> false;
        };
    }
}
