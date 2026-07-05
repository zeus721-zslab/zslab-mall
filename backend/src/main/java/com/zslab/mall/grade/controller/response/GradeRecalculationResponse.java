package com.zslab.mall.grade.controller.response;

import com.zslab.mall.grade.service.GradeRecalculationResult;

/**
 * 등급 재산정 배치 응답(Track 51 Phase 3). 총 대상·성공·실패 카운트를 담는다(SettlementBatchResponse 관례 정합).
 */
public record GradeRecalculationResponse(int total, int success, int failure) {

    public static GradeRecalculationResponse from(GradeRecalculationResult result) {
        return new GradeRecalculationResponse(result.total(), result.success(), result.failure());
    }
}
