package com.zslab.mall.stats.controller.response;

import com.zslab.mall.stats.enums.StatsAxis;
import java.util.List;

/**
 * 매출 분해 테이블 응답(Track 87·D-181·{@code GET /api/v1/admin/stats/sales/breakdown}). 매출 기준만 분해하며 환불은 축별로 나누지 않는다
 * (refund→claim→order_item theta-join 비용·요약의 refund로 충분). totalRevenue는 기간 전체 결제완료 매출(order.total_price 합)로
 * 비중 재계산 근거이며, rows는 현재 기간에 존재하는 키만 매출 내림차순으로 전량 담는다(페이징 없음·비교 기간에만 있는 키는 제외).
 * 카테고리 축은 product.category_id(현행) 경유라 상품 카테고리 변경 시 과거 귀속이 바뀐다.
 */
public record AdminSalesBreakdownResponse(
        StatsAxis axis,
        String parentKey,
        long totalRevenue,
        List<SalesBreakdownRowResponse> rows) {
}
