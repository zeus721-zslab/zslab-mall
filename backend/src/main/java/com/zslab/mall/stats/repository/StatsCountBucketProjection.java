package com.zslab.mall.stats.repository;

/** 구간 건수 집계 1행(Track 88 공용·금액 없음). bucket 규약은 {@link StatsBucketProjection}과 같다. */
public interface StatsCountBucketProjection {

    String getBucket();

    Long getBucketCount();
}
