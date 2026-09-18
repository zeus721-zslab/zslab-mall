package com.zslab.mall.settlement.service;

import com.zslab.mall.settlement.entity.Settlement;

/**
 * 정산 재생성 결과(Track 85). deletedSettlementId는 삭제된 기존 정산, regenerated는 재집계로 새로 만든 정산(대상 품목이 없으면 null·삭제만).
 */
public record SettlementRegenerateResult(
        Long deletedSettlementId,
        Settlement regenerated) {
}
