package com.zslab.mall.seller.controller.response;

import com.zslab.mall.auth.enums.RoleCode;
import java.time.LocalDateTime;

/**
 * 구성원 추가(201) 응답(D-204). 구성원 행 필드({@link AdminSellerDetailResponse.Member}와 같은 키)에 {@code temporaryPassword}를 더한다 —
 * 신규 계정 생성({@code newUser}) 시에만 값이 있고 기존 회원 연결은 null. 셀러 상세 구성원 목록은 별도 record라 평문 필드가 없다.
 * {@code toString}은 평문을 가린다.
 */
public record AdminSellerMemberAddResponse(String userPublicId, String email, String name, RoleCode roleCode,
        LocalDateTime withdrawnAt, LocalDateTime joinedAt, String temporaryPassword) {

    private static final String MASK = "****";

    public static AdminSellerMemberAddResponse of(AdminSellerDetailResponse.Member member, String temporaryPassword) {
        return new AdminSellerMemberAddResponse(member.userPublicId(), member.email(), member.name(), member.roleCode(),
                member.withdrawnAt(), member.joinedAt(), temporaryPassword);
    }

    @Override
    public String toString() {
        return "AdminSellerMemberAddResponse[userPublicId=" + userPublicId + ", roleCode=" + roleCode
                + ", temporaryPassword=" + (temporaryPassword == null ? null : MASK) + "]";
    }
}
