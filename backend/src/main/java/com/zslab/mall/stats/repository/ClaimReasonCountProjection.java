package com.zslab.mall.stats.repository;

/** 클레임 사유 코드별 건수(Track 88). reasonCode는 DB 무제약 VARCHAR 원문. */
public interface ClaimReasonCountProjection {

    String getReasonCode();

    Long getClaimCount();
}
