package com.zslab.mall.auth.controller.request;

/**
 * 운영자 목록 역할 필터(Track 89-E). ADMIN 계열 2값만 허용해 BUYER·SELLER_* 코드로 회원을 뒤지는 경로를 막는다(허용 외 값은
 * Spring 변환 실패 400·{@code AdminMemberStatusFilter} 선례).
 */
public enum AdminOperatorRoleFilter {
    SUPER_ADMIN,
    ADMIN_OPERATOR
}
