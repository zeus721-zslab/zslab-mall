package com.zslab.mall.settlement.service;

import com.zslab.mall.settlement.entity.Settlement;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 월 정산 배치 실행 결과(Track 48 P3). 요청 기간과 실제 생성된 Settlement 목록을 담는다. 중복·계좌부재로 skip된 seller는
 * 포함하지 않는다(생성분만). Controller가 응답 DTO로 변환한다.
 */
public record SettlementBatchResult(
        LocalDateTime periodStart,
        LocalDateTime periodEnd,
        List<Settlement> created) {
}
