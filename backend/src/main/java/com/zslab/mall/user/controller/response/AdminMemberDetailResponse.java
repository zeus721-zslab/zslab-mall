package com.zslab.mall.user.controller.response;

import com.zslab.mall.grade.enums.BuyerGradeCode;
import com.zslab.mall.user.enums.GradeSource;
import java.time.LocalDateTime;
import java.util.List;

/** 관리자 회원 상세(Track 84). passwordHash 등 자격증명은 제외한다. */
public record AdminMemberDetailResponse(
        String publicId,
        String name,
        String email,
        String phone,
        LocalDateTime createdAt,
        LocalDateTime withdrawnAt,
        boolean passwordChangeRequired,
        Grade grade,
        List<AddressResponse> addresses) {

    /** 등급 정보(buyer_profile). */
    public record Grade(BuyerGradeCode code, GradeSource source, LocalDateTime lockedUntil) {
    }
}
