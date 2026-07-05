package com.zslab.mall.settlement.controller.response;

import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.enums.SettlementStatus;
import java.time.LocalDateTime;

/**
 * 정산 전이(confirm·pay) 결과 응답(Track 49). 전이 후 상태와 지급 시각을 노출한다. paidAt은 PAID 전이 후에만 채워진다
 * (STL-5·PENDING·CONFIRMED 상태에서는 null).
 */
public record SettlementTransitionResponse(
        Long settlementId,
        SettlementStatus status,
        LocalDateTime paidAt) {

    public static SettlementTransitionResponse from(Settlement settlement) {
        return new SettlementTransitionResponse(
                settlement.getId(), settlement.getStatus(), settlement.getPaidAt());
    }
}
