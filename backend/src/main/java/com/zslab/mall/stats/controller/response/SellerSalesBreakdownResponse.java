package com.zslab.mall.stats.controller.response;

import com.zslab.mall.stats.enums.SellerStatsAxis;
import java.util.List;

/**
 * 셀러 매출 분해 테이블 응답(Track 90-E-1·{@code GET /api/v1/seller/stats/sales/breakdown}). totalRevenue는 셀러 기간 매출(자기 품목 합)로
 * 비중 근거이며 rows는 현재 기간에 존재하는 키만 매출 내림차순 전량(페이징 없음). 환불은 축별로 나누지 않는다(관리자 D-181과 동일).
 */
public record SellerSalesBreakdownResponse(
        SellerStatsAxis axis,
        long totalRevenue,
        List<SellerSalesBreakdownRowResponse> rows) {
}
