package com.zslab.mall.settlement.controller.request;

import jakarta.validation.constraints.NotNull;

/**
 * 월 정산 배치 생성 요청(Track 48 P3). year/month의 형식(필수 존재)은 Bean Validation이, 유효 범위(월 1~12·연도 2000~2100)는
 * Service({@code SettlementCreationService})가 도메인 규칙으로 검증한다.
 */
public record CreateMonthlySettlementRequest(
        @NotNull Integer year,
        @NotNull Integer month) {
}
