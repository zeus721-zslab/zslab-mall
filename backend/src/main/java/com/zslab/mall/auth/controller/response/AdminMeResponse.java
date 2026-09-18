package com.zslab.mall.auth.controller.response;

import com.zslab.mall.auth.enums.RoleCode;
import java.util.List;

/**
 * 현재 로그인한 관리자 자신(Track 89-E). JWT는 coarse role(ADMIN)과 내부 userId만 담아 FE가 SUPER_ADMIN 여부·자기 publicId를
 * 알 수 없으므로 서버가 user_role 실조회로 알려준다. {@code roles}는 보유 역할 전체, {@code superAdmin}은 SUPER_ADMIN 전용
 * 작업(부여·회수) 버튼 활성 판정용이다(실인가는 서비스가 별도로 강제).
 */
public record AdminMeResponse(
        String userPublicId,
        String name,
        String email,
        List<RoleCode> roles,
        boolean superAdmin) {
}
