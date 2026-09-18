package com.zslab.mall.stats.controller.response;

/**
 * 기간 회원 요약(Track 88·D-182). newCount = BUYER role 가입(created_at·탈퇴자 포함·D-180), withdrawnCount = 탈퇴(withdrawn_at),
 * activeTotal = 기간 종료 시점 활성 누적(가입 누계 − 탈퇴 누계). buyerCount = 기간 내 결제 구매자 수, repeatBuyerCount = 그중 2회 이상 결제,
 * repurchaseRate = repeatBuyerCount / buyerCount × 100(소수 2자리·0이면 0).
 */
public record MemberSummaryResponse(
        long newCount,
        long withdrawnCount,
        long activeTotal,
        double repurchaseRate,
        long buyerCount,
        long repeatBuyerCount) {

    public static MemberSummaryResponse of(long newCount, long withdrawnCount, long activeTotal, long buyerCount,
            long repeatBuyerCount) {
        return new MemberSummaryResponse(newCount, withdrawnCount, activeTotal,
                StatsRatio.percent(repeatBuyerCount, buyerCount), buyerCount, repeatBuyerCount);
    }

    /** 비교 기간 데이터 유무(가입·탈퇴·구매자 모두 0이면 없음 → 응답 null). getter 패턴 이름은 Jackson 누출 때문에 피한다(Track 87). */
    public boolean hasNoData() {
        return newCount == 0 && withdrawnCount == 0 && buyerCount == 0;
    }
}
