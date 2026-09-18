package com.zslab.mall.dashboard.controller.response;

/** 오늘·이번 달 지표와 비교값(전일 전체·전월 전체 — 동기 대비 아님·단순화·D-180). */
public record DashboardSummaryResponse(
        DashboardPeriodMetrics today,
        DashboardPeriodMetrics previousDay,
        DashboardPeriodMetrics thisMonth,
        DashboardPeriodMetrics previousMonth) {
}
