package com.zslab.mall.settlement.repository;

import com.zslab.mall.settlement.enums.SettlementStatus;

/** 월 상태별 정산 건수·금액 합 projection(Track 85 관리자 목록 합계). */
public interface SettlementStatusTotalProjection {

    SettlementStatus getStatus();

    Long getSettlementCount();

    Long getGrossAmount();

    Long getFeeAmount();

    Long getRefundAmount();

    Long getCarryoverAmount();

    Long getNetAmount();
}
