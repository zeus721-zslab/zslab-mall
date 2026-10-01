package com.zslab.mall.inbox.collector;

import java.util.List;

/**
 * 한 유형의 수집 결과. {@code rows}는 기준 시각 오름차순 최대 limit건이고 {@code total}은 상한과 무관한 전체 건수다.
 */
public record InboxSlice(List<InboxRow> rows, long total) {
}
