package com.zslab.mall.settlement.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.settlement.service.SettlementBatchResult;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 월 정산 배치 생성 응답(Track 48 P3). 요청 기간·생성 건수·seller별 정산 요약을 담는다. 중복·계좌부재로 skip된 seller는
 * 포함하지 않는다(생성분만).
 */
public record SettlementBatchResponse(
        int year,
        int month,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime periodStart,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime periodEnd,
        int createdCount,
        List<SettlementLineResponse> settlements) {

    public static SettlementBatchResponse of(int year, int month, SettlementBatchResult result) {
        List<SettlementLineResponse> lines = result.created().stream()
                .map(SettlementLineResponse::from)
                .toList();
        return new SettlementBatchResponse(
                year, month, result.periodStart(), result.periodEnd(), lines.size(), lines);
    }
}
