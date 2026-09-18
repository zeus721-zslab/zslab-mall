package com.zslab.mall.stats.repository;

/** 기간 COMPLETED 환불 건수·금액 합(Track 88). */
public interface RefundTotalsProjection {

    Long getRefundCount();

    Long getRefundAmount();
}
