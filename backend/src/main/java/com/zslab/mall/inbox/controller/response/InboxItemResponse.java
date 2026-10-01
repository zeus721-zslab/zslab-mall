package com.zslab.mall.inbox.controller.response;

import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.inbox.collector.InboxRow;
import com.zslab.mall.inbox.enums.InboxItemType;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 인박스 항목 1건(D-248).
 *
 * @param ref       원천 식별자(publicId 또는 id 문자열 · 클레임 후속은 "클레임 publicId:단계 코드") — 보류 요청에 그대로 쓴다.
 *                  화면 이동은 targetKey와 ':' 앞 식별자로 한다
 * @param targetKey 이동할 원천 리소스 종류({@link InboxItemType#targetKey()})
 * @param baseAt    기준 시각(재고 임박·기준값 없는 항목은 null)
 * @param dueAt     기한(기한 없음 null)
 * @param overdue   기한이 지났는가(기한 없음 false)
 */
public record InboxItemResponse(
        InboxItemType type,
        String ref,
        String title,
        String subtitle,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime baseAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime dueAt,
        boolean overdue,
        String targetKey) {

    public static InboxItemResponse of(InboxRow row, LocalDateTime now) {
        boolean overdue = row.dueAt() != null && row.dueAt().isBefore(now);
        return new InboxItemResponse(row.type(), row.ref(), row.title(), row.subtitle(), row.baseAt(), row.dueAt(), overdue,
                row.type().targetKey());
    }
}
