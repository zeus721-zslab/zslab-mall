package com.zslab.mall.settlement.event;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 정산 정상처리(PENDING → CONFIRMED) 도메인 이벤트(Track 85). 소비처: 셀러 SMS 적재({@code NotificationSettlementConfirmedHandler}).
 * 발행 트랜잭션 커밋 후(AFTER_COMMIT) 소비되며 실패해도 전이는 유지된다.
 */
public record SettlementConfirmed(
        Long settlementId,
        Long sellerId,
        LocalDateTime periodStart,
        LocalDateTime periodEnd,
        Long netAmount,
        LocalDate scheduledPayDate,
        LocalDateTime occurredAt) {
}
