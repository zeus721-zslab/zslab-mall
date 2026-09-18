package com.zslab.mall.category.controller.response;

import java.util.List;

/**
 * 관리자 카테고리 목록 응답(Track 89-C D-185). 페이징 없음(루트 소량). 정렬은 sort_order·id 오름차순.
 *
 * @param defaultCommissionRate 플랫폼 기본 수수료율(bp·{@code settlement.default-commission-rate}·환경변수 유래). 카테고리 율이 미설정일 때
 *                              실제 적용되는 값을 화면이 함께 보여주기 위해 응답에 싣는다 — FE 상수로 복제하면 환경변수와 어긋난다.
 */
public record AdminCategoryListResponse(
        int defaultCommissionRate,
        List<AdminCategorySummaryResponse> items) {
}
