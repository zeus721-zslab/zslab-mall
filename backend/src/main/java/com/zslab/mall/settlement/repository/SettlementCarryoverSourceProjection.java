package com.zslab.mall.settlement.repository;

import java.time.LocalDateTime;

/** 이월 대상 음수 정산 projection(Track 104-3b·settlement_item CARRYOVER 소스). */
public interface SettlementCarryoverSourceProjection {

    Long getSettlementId();

    Long getSellerId();

    Long getNetAmount();

    LocalDateTime getPeriodEnd();
}
