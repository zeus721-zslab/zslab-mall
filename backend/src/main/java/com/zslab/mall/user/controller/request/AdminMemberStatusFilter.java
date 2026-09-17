package com.zslab.mall.user.controller.request;

/** 관리자 회원 목록 상태 필터(Track 84). ACTIVE=withdrawn_at NULL·WITHDRAWN=withdrawn_at NOT NULL. 허용 외 값은 Spring 변환 실패 400. */
public enum AdminMemberStatusFilter {
    ACTIVE,
    WITHDRAWN
}
