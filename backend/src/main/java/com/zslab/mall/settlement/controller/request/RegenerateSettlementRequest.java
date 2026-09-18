package com.zslab.mall.settlement.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 정산 재생성 요청(Track 85). 사유는 감사 DELETE diff에 기록되므로 필수(1~200자).
 */
public record RegenerateSettlementRequest(
        @NotBlank @Size(max = 200) String reason) {
}
