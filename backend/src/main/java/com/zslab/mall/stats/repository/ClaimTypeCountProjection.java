package com.zslab.mall.stats.repository;

import com.zslab.mall.claim.enums.ClaimType;

/** 클레임 유형별 건수(Track 88). */
public interface ClaimTypeCountProjection {

    ClaimType getClaimType();

    Long getClaimCount();
}
