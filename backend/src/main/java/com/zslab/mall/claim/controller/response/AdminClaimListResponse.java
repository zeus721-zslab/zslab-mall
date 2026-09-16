package com.zslab.mall.claim.controller.response;

import com.zslab.mall.order.controller.response.PagedResponse;
import java.util.List;

/**
 * 관리자 클레임 목록 응답(Track 80 D-169). {@link PagedResponse} 5필드에 처리 대기 건수 {@code pendingCount}(REQUESTED·유형 탭 필터
 * 반영·상태/기간/검색 필터 무관)를 동봉한다 — 목록 상단 표시용이며 별도 API·사이드바 배지는 두지 않는다.
 */
public record AdminClaimListResponse(
        List<AdminClaimSummaryResponse> items,
        int page,
        int size,
        long totalCount,
        boolean hasNext,
        long pendingCount) {

    public static AdminClaimListResponse from(PagedResponse<AdminClaimSummaryResponse> page, long pendingCount) {
        return new AdminClaimListResponse(page.items(), page.page(), page.size(), page.totalCount(), page.hasNext(),
                pendingCount);
    }
}
