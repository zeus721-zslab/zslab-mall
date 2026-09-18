package com.zslab.mall.stats.repository;

import com.zslab.mall.grade.enums.BuyerGradeCode;

/** 등급별 활성 회원 수(Track 88·buyer_profile 현재 등급). */
public interface GradeCountProjection {

    BuyerGradeCode getGradeCode();

    Long getMemberCount();
}
