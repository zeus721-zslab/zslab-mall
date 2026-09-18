package com.zslab.mall.dashboard.repository;

/** 월별("yyyy-MM")·일별("yyyy-MM-dd") 구간 집계 1행(Track 86). bucket은 DATE_FORMAT 결과 문자열. */
public interface DashboardBucketProjection {

    String getBucket();

    Long getOrderCount();

    Long getAmount();
}
