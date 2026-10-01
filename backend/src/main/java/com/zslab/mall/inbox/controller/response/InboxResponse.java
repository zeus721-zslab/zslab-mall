package com.zslab.mall.inbox.controller.response;

import com.zslab.mall.inbox.enums.InboxItemType;
import java.util.List;

/**
 * 인박스 탭 1개(D-248). 도메인 교차 페이징은 없다.
 *
 * @param items     기한 오름차순(기한 없음은 뒤) 최대 200건
 * @param counts    이 탭의 유형별 전체 건수(보류 제외·type 필터와 무관하게 보는 사람의 모든 유형)
 * @param truncated 상한 때문에 빠진 항목이 있는가
 */
public record InboxResponse(List<InboxItemResponse> items, List<TypeCount> counts, boolean truncated) {

    public record TypeCount(InboxItemType type, long count) {
    }
}
