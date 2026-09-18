package com.zslab.mall.auth.controller.response;

import com.zslab.mall.auth.enums.RoleCode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 운영자 목록 행(Track 89-E). {@code roles}는 ADMIN 계열(SUPER_ADMIN·ADMIN_OPERATOR)만 담고, 일반회원 겸직은
 * {@code hasBuyerRole}(BUYER role 보유 = 회원 목록에도 나오는 계정)로 따로 표기한다.
 */
public record AdminOperatorSummaryResponse(
        String userPublicId,
        String name,
        String email,
        List<RoleCode> roles,
        boolean hasBuyerRole,
        LocalDateTime createdAt,
        LocalDateTime withdrawnAt) {
}
