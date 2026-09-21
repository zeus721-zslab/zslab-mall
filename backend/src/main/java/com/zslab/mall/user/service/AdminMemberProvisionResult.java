package com.zslab.mall.user.service;

import com.zslab.mall.user.entity.User;

/**
 * 관리자 주도 계정 생성 결과(D-204). 생성된 회원과 화면 1회 표시용 임시 비밀번호 평문을 함께 돌려준다. 호출자는 평문을 응답 본문에만
 * 싣고 로그·감사·상태 저장에 쓰지 않는다. {@code toString}은 평문을 가린다.
 */
public record AdminMemberProvisionResult(User user, String temporaryPassword) {

    private static final String MASK = "****";

    @Override
    public String toString() {
        return "AdminMemberProvisionResult[userId=" + (user == null ? null : user.getId()) + ", temporaryPassword=" + MASK + "]";
    }
}
