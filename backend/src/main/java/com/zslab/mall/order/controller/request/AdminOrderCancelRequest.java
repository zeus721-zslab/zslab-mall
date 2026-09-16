package com.zslab.mall.order.controller.request;

import com.zslab.mall.claim.enums.ClaimReasonCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 관리자 주문 취소 요청(Track 79 D-168). 사유 코드 필수·메모 선택(≤500·사용자 {@code ClaimRequestRequest}와 동일 규칙).
 * {@code orderItemPublicIds}는 결제 후 주문의 항목 단위 부분 취소 대상이며, 미결제(PENDING_PAYMENT) 주문 취소 시엔 무시된다
 * (주문 전체 종료). 결제 후 주문에서 비어 있으면 취소 가능한 전 항목이 대상이다.
 */
public record AdminOrderCancelRequest(
        @NotNull ClaimReasonCode reasonCode,
        @Size(max = 500, message = "reasonDetail은 500자 이하여야 합니다.") String reasonDetail,
        List<@Pattern(regexp = "^oit_[0-9A-Z]{26}$",
                message = "orderItemPublicId 형식이 올바르지 않습니다(oit_ + ULID 26자).") String> orderItemPublicIds) {
}
