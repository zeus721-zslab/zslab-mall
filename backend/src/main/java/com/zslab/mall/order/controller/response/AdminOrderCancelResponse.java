package com.zslab.mall.order.controller.response;

import com.zslab.mall.claim.enums.ClaimStatus;
import java.util.List;

/**
 * 관리자 주문 취소 응답(Track 79 D-168). 미결제 종료면 {@code orderStatus=PAYMENT_EXPIRED}·claims 비어 있음, 결제 후면
 * 항목별 생성·승인된 Claim(APPROVED) 목록.
 */
public record AdminOrderCancelResponse(
        String orderId,
        String orderStatus,
        List<CancelledItem> claims) {

    public record CancelledItem(String claimId, String orderItemId, ClaimStatus status) {
    }
}
