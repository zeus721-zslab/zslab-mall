package com.zslab.mall.seller.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 셀러 구성원 추가 요청(Track 89-G). 대상은 기존 회원({@code userPublicId}) <b>또는</b> 신규 계정({@code newUser}) 중 정확히 하나다
 * (둘 다·둘 다 없음 400 VALIDATION_FAILED·{@link #isExactlyOneTarget}). role은 SELLER_* 3값만 허용하는 4층위 DTO 층
 * ({@code AdminSellerStatusChangeRequest} 선례·위반 400). 추가에는 사유를 받지 않는다(부여는 가역·감사 CREATE로 충분·D-186 §1-A 5).
 */
public record AdminSellerMemberAddRequest(
        String userPublicId,
        @Valid AdminSellerMemberNewUserRequest newUser,
        @NotBlank @Pattern(regexp = "^SELLER_(OWNER|MANAGER|STAFF)$") String role) {

    /** userPublicId XOR newUser — Bean Validation getter 관례(is-prefix)로 클래스 수준 형식 검증. */
    @AssertTrue(message = "userPublicId 또는 newUser 중 하나만 지정해야 합니다.")
    public boolean isExactlyOneTarget() {
        boolean hasUser = userPublicId != null && !userPublicId.isBlank();
        return hasUser != (newUser != null);
    }
}
