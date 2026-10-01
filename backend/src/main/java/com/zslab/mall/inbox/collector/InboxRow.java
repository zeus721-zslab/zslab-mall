package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import java.time.LocalDateTime;

/**
 * 수집된 대기 항목 1건.
 *
 * @param ref     원천 도메인 API 식별자(publicId 또는 id 문자열) — 보류 키와 화면 이동에 같은 값을 쓴다
 * @param baseAt  기준 시각(기한이 없는 유형은 null)
 * @param dueAt   기한(기한이 없는 유형은 null)
 */
public record InboxRow(InboxItemType type, String ref, String title, String subtitle, LocalDateTime baseAt,
        LocalDateTime dueAt) {
}
