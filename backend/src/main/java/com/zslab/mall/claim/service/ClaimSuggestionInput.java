package com.zslab.mall.claim.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.order.enums.OrderItemStatus;

/**
 * 제안 규칙의 입력(D-250 · 기존 값만). 그대로 직렬화해 제안 기록의 입력 스냅샷으로 저장한다(D-220 보완 — 채택/거부 역추적).
 *
 * @param reasonCode             클레임 사유 코드({@code claim.reason_code} 원문)
 * @param previousItemStatus     요청 시점 품목 상태
 * @param exchangeOptionOnSale   교환 옵션 판매 가능 여부(교환이 아니면 null)
 * @param exchangeAvailableStock 교환 옵션 가용 재고(교환이 아니면 null · 재고 행 없음은 0)
 */
// 스냅샷은 기록이라 null 키도 남긴다(전역 non_null 직렬화 설정이 바뀌어도 기록 모양이 그대로이게).
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ClaimSuggestionInput(
        ClaimType type,
        String reasonCode,
        long attachmentCount,
        OrderItemStatus previousItemStatus,
        int quantity,
        Boolean exchangeOptionOnSale,
        Integer exchangeAvailableStock) {
}
