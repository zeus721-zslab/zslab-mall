package com.zslab.mall.stats.repository;

/** 구간 집계 1행(Track 88 공용). bucket은 DATE_FORMAT 결과 문자열(일 yyyy-MM-dd·주 yyyy-ww(ISO %x-%v)·월 yyyy-MM)·count 건수·amount 금액 합. */
public interface StatsBucketProjection {

    String getBucket();

    Long getBucketCount();

    Long getAmount();
}
