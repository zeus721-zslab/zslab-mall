package com.zslab.mall.user.controller.request;

/** 관리자 회원 목록 정렬(Track 84·가입일 기준). 허용 외 값은 Spring 변환 실패 400(AdminOrderSort 선례). */
public enum AdminMemberSort {
    LATEST,
    OLDEST
}
