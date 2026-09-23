package com.zslab.mall.claim.enums;

/**
 * 클레임 처리 상태(A분류·A#14·4값·CLM-4). DDL {@code claim.status} ENUM 정합.
 *
 * <p><b>전이 매트릭스(state-machine.md §2 1:1)</b>:
 * <ul>
 *   <li>REQUESTED → APPROVED: 관리자/판매자 승인</li>
 *   <li>REQUESTED → REJECTED: 관리자/판매자 거절</li>
 *   <li>APPROVED → COMPLETED: 환불/수거/교환발송 완료</li>
 *   <li>종결 상태(REJECTED·COMPLETED) → 어떤 전이도 불가(CLM-1). 재요청은 새 Claim 행(CLM-2)</li>
 * </ul>
 *
 * <p>본 트랙(Track 5)이 실제 구동하는 전이는 <b>APPROVED → COMPLETED</b>(Refund.COMPLETED 콜백·Claim.type=CANCEL) 1건이며,
 * REQUESTED 진입·APPROVED 승인은 후속 트랙 소관이다(expected-spec §3.2). 단 enum·전이 매트릭스는 4상태 전체를 박제한다.
 */
public enum ClaimStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    COMPLETED;

    /**
     * 현재 상태에서 {@code next}로의 전이가 합법인지 판정한다(state-machine.md §2·CLM-4).
     *
     * @param next 목표 상태
     * @return 합법 전이면 true. 동일 상태 전이·종결 상태(REJECTED·COMPLETED)에서의 전이는 false(CLM-1)
     */
    public boolean canTransitionTo(ClaimStatus next) {
        return switch (this) {
            case REQUESTED -> next == APPROVED || next == REJECTED;
            case APPROVED -> next == COMPLETED;
            // 종결 상태는 어떤 전이도 불가(CLM-1·재요청은 새 행 CLM-2)
            case REJECTED, COMPLETED -> false;
        };
    }

    /**
     * 아직 진행 중(활성)인 클레임인지 판정한다(Track 101-B). 활성 = REQUESTED·APPROVED이며 종결 상태(REJECTED·COMPLETED)는
     * 비활성이다.
     *
     * <p>거르는 일은 전부 DB가 한다 — {@code ClaimRepository.existsActiveByOrderItemId}·{@code existsActiveByBuyerId}·
     * {@code countActiveBySellerId}·{@code findActiveByOrderItemIdIn} 네 JPQL이 같은 상태 집합을 인라인으로 갖고 있다.
     * 이 메서드는 그 기준의 <b>Java 쪽 단일 선언</b>이며, {@code ClaimActiveStatusConsistencyTest}가 모든 상태에 실제 행을
     * 넣어 네 쿼리 결과와 이 판정을 대조한다(외부 검토 반영). 기준을 바꿀 때는 이 메서드와 JPQL 네 곳을 함께 고친다 —
     * 한쪽만 고치면 그 테스트가 먼저 깨진다.
     */
    public boolean isActive() {
        return this == REQUESTED || this == APPROVED;
    }
}
