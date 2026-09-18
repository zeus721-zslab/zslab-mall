package com.zslab.mall.settlement.repository;

/** 정산별 품목 건수 projection(Track 85 목록 enrich). */
public interface SettlementItemCountProjection {

    Long getSettlementId();

    Long getItemCount();
}
