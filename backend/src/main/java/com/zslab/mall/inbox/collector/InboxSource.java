package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;

/**
 * 인박스 유형 1개의 수집기(D-248). 대기 여부는 원천 상태에서만 판정하며(D-220), 수집 결과에서는 보는 사람의 유효 보류 항목을 뺀다.
 */
public interface InboxSource {

    InboxItemType type();

    /** 탭 범위의 항목(기준 시각 오름차순·상한 적용)과 전체 건수. */
    InboxSlice collect(InboxViewer viewer, InboxWindow window);

    /** 탭 범위의 전체 건수(보류 제외). */
    long count(InboxViewer viewer, InboxWindow window);

    /** {@code ref}가 지금 보는 사람의 인박스 대기 항목인가(탭·보류 무관). 셀러는 자기 소유만 true다. */
    boolean isPending(InboxViewer viewer, String ref);
}
