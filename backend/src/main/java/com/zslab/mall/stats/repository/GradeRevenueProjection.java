package com.zslab.mall.stats.repository;

import com.zslab.mall.grade.enums.BuyerGradeCode;

/** 등급별 기간 매출 합(Track 88·구매자의 현재 등급 경유). */
public interface GradeRevenueProjection {

    BuyerGradeCode getGradeCode();

    Long getRevenue();
}
