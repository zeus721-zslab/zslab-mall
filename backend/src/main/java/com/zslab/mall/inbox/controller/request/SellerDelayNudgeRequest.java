package com.zslab.mall.inbox.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 셀러 지연 일괄 독촉 요청(D-252). 패널 단건 독촉도 1건 목록으로 보낸다. 입력 순서대로 처리한다.
 */
public record SellerDelayNudgeRequest(
        @NotEmpty @Size(max = SellerDelayNudgeRequest.MAX_SELLERS) List<@NotBlank String> sellerPublicIds) {

    /** 한 번에 독촉할 셀러 수 상한 — 셀러마다 SMS를 요청 스레드에서 동기 발송한다(클레임 일괄 승인 D-250과 같은 값). */
    public static final int MAX_SELLERS = 20;
}
