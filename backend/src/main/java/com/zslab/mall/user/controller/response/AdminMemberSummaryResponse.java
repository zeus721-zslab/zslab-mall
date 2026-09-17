package com.zslab.mall.user.controller.response;

import com.zslab.mall.grade.enums.BuyerGradeCode;
import java.time.LocalDateTime;

/** 관리자 회원 목록 행(Track 84). lastPaidAt은 결제 완료 주문의 최근 paid_at(없으면 null). */
public record AdminMemberSummaryResponse(
        String publicId,
        String name,
        String email,
        String phone,
        BuyerGradeCode gradeCode,
        LocalDateTime createdAt,
        LocalDateTime lastPaidAt,
        LocalDateTime withdrawnAt) {
}
