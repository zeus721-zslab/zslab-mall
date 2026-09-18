package com.zslab.mall.stats.controller.response;

/**
 * 구매 상위 회원 1행(기간 내 매출 내림차순·동률 buyer id 오름차순·최대 20). name·email은 관리자 회원 목록과 같은 원문 표기
 * (마스킹 없음·비식별화·soft-delete 회원은 null 그대로). userPublicId는 회원 상세 링크용.
 */
public record TopBuyerResponse(
        String userPublicId,
        String name,
        String email,
        long orderCount,
        long revenue) {
}
