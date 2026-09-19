package com.zslab.mall.user.controller.response;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.grade.enums.BuyerGradeCode;
import com.zslab.mall.user.enums.GradeSource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 회원 상세(Track 84). passwordHash 등 자격증명은 제외한다. {@code sellerMembership}은 셀러 구성원일 때만 실린다(Track 89-G STEP 498·
 * D-189) — 탈퇴 다이얼로그 경고용이며 탈퇴를 차단하지 않는다(확정 4). 목록 응답에는 싣지 않는다(N+1 회피).
 */
public record AdminMemberDetailResponse(
        String publicId,
        String name,
        String email,
        String phone,
        LocalDateTime createdAt,
        LocalDateTime withdrawnAt,
        boolean passwordChangeRequired,
        Grade grade,
        List<AddressResponse> addresses,
        SellerMembership sellerMembership) {

    /** 등급 정보(buyer_profile). */
    public record Grade(BuyerGradeCode code, GradeSource source, LocalDateTime lockedUntil) {
    }

    /**
     * 셀러 소속(seller_user·soft-delete 셀러는 제외). {@code lastActiveMember} = 이 회원이 해당 셀러의 <b>마지막 활성 구성원</b>(이 회원이 활성이고
     * 그 외 활성 구성원이 0명·역할 무관). 셀러 로그인·API 접근은 seller_user 행 존재만으로 결정되고 역할은 권한에 관여하지 않으므로(D-189 §1-A 2),
     * "탈퇴하면 이 셀러에 로그인할 수 있는 사람이 없어진다"는 판정은 역할과 무관하게 활성 구성원 수로 센다. 구성원 제거 가드의 "마지막 활성 OWNER"
     * (역할 한정)와는 다른 개념이다. 이 회원이 이미 탈퇴했으면 항상 false(활성이 아니므로 잃을 것이 없음).
     */
    public record SellerMembership(String sellerPublicId, String companyName, RoleCode roleCode, boolean lastActiveMember) {
    }
}
