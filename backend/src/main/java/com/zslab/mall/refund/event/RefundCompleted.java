package com.zslab.mall.refund.event;

import java.time.LocalDateTime;

/**
 * 환불 완료 도메인 이벤트(expected-spec §8). Spring ApplicationEvent로 발행한다.
 *
 * <p><b>발행/소비 시점 분리(D-69)</b>: Publisher는 {@code RefundService.markCompleted}에서 save→publish(D-29·flush 없음),
 * Consumer는 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}로 Refund UPDATE 커밋 후 진입한다.
 *
 * <p>payload는 사실 통지 원칙으로 식별자·금액·시각에 한정한다. 소비측(Claim·Payment 핸들러)은 식별자로 필요한 행을 재조회한다.
 *
 * @param refundId   환불 행 id(Refund.id)
 * @param claimId    상위 클레임 id(Claim.id)·Claim 종결 핸들러 라우팅
 * @param paymentId  결제 행 id(Payment.id)·Payment 취소 핸들러 누적 검증
 * @param amount     환불 금액(KRW 정수)
 * @param refundedAt COMPLETED 전이 시스템 시각(D-70)
 */
public record RefundCompleted(
        Long refundId,
        Long claimId,
        Long paymentId,
        Long amount,
        LocalDateTime refundedAt) {
}
