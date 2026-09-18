package com.zslab.mall.stats.repository;

/** 구간 집계 1행(Track 87). bucket은 DATE_FORMAT 결과 문자열(일 yyyy-MM-dd·주 yyyy-ww(ISO %x-%v)·월 yyyy-MM). */
public interface SalesBucketProjection {

    String getBucket();

    Long getOrderCount();

    Long getAmount();
}
