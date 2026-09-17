package com.zslab.mall.user.controller.request;

import com.zslab.mall.grade.enums.BuyerGradeCode;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 관리자 수동 등급 변경 요청(Track 84). gradeCode는 enum 타입 바인딩으로 형식 검증한다(불일치 값은 Jackson 역직렬화 실패 → 400·
 * ClaimRequestRequest 패턴). lockedUntil은 오늘 이후 날짜(그 날 23:59:59.999999까지 AUTO 재산정 skip).
 */
public record AdminMemberGradeRequest(
        @NotNull BuyerGradeCode gradeCode,
        @NotNull @Future(message = "lockedUntil은 오늘 이후 날짜여야 합니다.") LocalDate lockedUntil) {
}
