package com.zslab.mall.stats.controller.response;

import java.util.List;

/**
 * 셀러 상품 통계 응답(Track 90-E-3·D-200·{@code GET /api/v1/seller/stats/products}·관리자 대응 API 없음). 기간(양끝 포함·365일)만 받고 비교·버킷은 없다.
 * topProducts는 기간 내 판매 1건 이상 상품의 상위 {@code SellerProductStatsQueryService.RANK_LIMIT}건, bottomProducts는 상위에 들지 않은 나머지의 하위 최대 같은 건수(교집합 0·판매 상품이 10 이하면 빈 목록), unsoldProducts는 판매 상태
 * (SALE) 상품 중 기간 내 결제 품목 0, stockTurnover는 SALE 상품의 입고(기간)·판매(기간)·현재 가용·소진 예상일(상품 단위 합산),
 * soldOutOptionCount·saleOptionCount는 <b>현재 시점</b>(기간 무관)의 판매 중 옵션 재고 현황이다.
 */
public record SellerProductStatsResponse(
        long periodDays,
        List<SellerProductRankResponse> topProducts,
        List<SellerProductRankResponse> bottomProducts,
        List<SellerUnsoldProductResponse> unsoldProducts,
        List<SellerStockTurnoverResponse> stockTurnover,
        long soldOutOptionCount,
        long saleOptionCount) {
}
