package com.zslab.mall.claim.exception;

/**
 * 클레임이 요구되는 상태·정책에 부합하지 않아 처리를 진행할 수 없을 때 발생한다(CLM-3 책임). 전역적으로 422로 매핑된다(D-50·D-89 Q3).
 *
 * <p>대표 케이스:
 * <ul>
 *   <li>{@code RefundService.initiate}가 Claim.status=APPROVED를 요구하나 그렇지 않은 경우(미승인 환불 차단·CLM-3·Track 5)</li>
 *   <li>{@code Claim.transitionTo} 비합법 전이(canTransitionTo 위반·CLM-4·예: REQUESTED가 아닌데 approve)</li>
 *   <li>{@code ClaimService.request} CANCEL 외 유형(Q6)·동일 OrderItem 활성 클레임 중복(CLM-5)·취소 요청 불가 상태</li>
 * </ul>
 *
 * <p><b>재활용 결정(D-89 Q3·기조 4)</b>: Track 9 PR-B에서 신규 예외(InvalidClaimStateException) 신설을 폐기하고 본 클래스를 재활용한다.
 * {@code RefundInvariantViolationException}과 동일한 {@code RuntimeException + String message} 단순 패턴을 유지한다.
 */
public class ClaimInvalidStateException extends RuntimeException {

    public ClaimInvalidStateException(String message) {
        super(message);
    }
}
