package com.zslab.mall.settlement.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.settlement.entity.SettlementItem;
import com.zslab.mall.settlement.enums.SettlementItemType;
import java.time.LocalDateTime;

/** 정산 품목 스냅샷 행(Track 85·관리자·셀러 공용). */
public record SettlementItemResponse(
        Long id,
        SettlementItemType itemType,
        Long orderItemId,
        Long refundId,
        String orderPublicId,
        String productName,
        String optionLabel,
        int quantity,
        long amount,
        int commissionRate,
        long feeAmount,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime occurredAt) {

    public static SettlementItemResponse from(SettlementItem item) {
        return new SettlementItemResponse(item.getId(), item.getItemType(), item.getOrderItemId(), item.getRefundId(),
                item.getOrderPublicId(), item.getProductName(), item.getOptionLabel(), item.getQuantity(),
                item.getAmount(), item.getCommissionRate(), item.getFeeAmount(), item.getOccurredAt());
    }
}
