package com.zslab.mall.stats.controller.response;

import com.zslab.mall.grade.enums.BuyerGradeCode;

/**
 * 등급 분포 1행(Track 88·D-182). memberCount = 현재 등급(buyer_profile.grade_id) 보유 활성 회원 수·share = 전체 대비 %.
 * revenue = 그 등급 회원의 기간 내 결제 매출(order.total_price·paid_at)·revenueShare = 3등급 매출 합 대비 %(buyer_profile 없는 구매자는 제외·현재 전 구매자 보유). 주문 시점 등급 스냅샷이 없어
 * 현재 등급 경유이며 등급 재산정 시 과거 매출의 등급 귀속이 바뀐다. 3등급 전부 내린다(0건 포함·enum 순서).
 */
public record GradeDistributionResponse(
        BuyerGradeCode gradeCode,
        long memberCount,
        double share,
        long revenue,
        double revenueShare) {

    public static GradeDistributionResponse of(BuyerGradeCode gradeCode, long memberCount, long totalMembers, long revenue,
            long totalRevenue) {
        return new GradeDistributionResponse(gradeCode, memberCount, StatsRatio.percent(memberCount, totalMembers), revenue,
                StatsRatio.percent(revenue, totalRevenue));
    }
}
