package com.zslab.mall.claim.controller.response;

/**
 * 관리자 클레임 단건(D-250 · 인박스 상세 패널). 목록 행과 같은 필드에 처리 제안을 더한다.
 *
 * @param claim      목록 행과 같은 조립 결과
 * @param suggestion 처리 제안(REQUESTED만 · 그 외 null — 제안은 접수 건의 승인·거부 판단용이다)
 */
public record AdminClaimDetailResponse(AdminClaimSummaryResponse claim, ClaimSuggestionResponse suggestion) {
}
