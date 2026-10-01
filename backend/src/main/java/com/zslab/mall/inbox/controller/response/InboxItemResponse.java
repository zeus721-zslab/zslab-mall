package com.zslab.mall.inbox.controller.response;

import com.zslab.mall.claim.enums.ClaimSuggestion;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.inbox.collector.InboxRow;
import com.zslab.mall.inbox.enums.InboxItemType;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 인박스 항목 1건(D-248).
 *
 * @param ref        원천 식별자(publicId 또는 id 문자열 · 클레임 후속은 "클레임 publicId:단계 코드") — 보류 요청에 그대로 쓴다.
 *                   화면 이동은 targetKey와 ':' 앞 식별자로 한다
 * @param targetKey  이동할 원천 리소스 종류({@link InboxItemType#targetKey()})
 * @param baseAt     기준 시각(재고 임박·기준값 없는 항목은 null)
 * @param dueAt      기한(기한 없음 null)
 * @param overdue    기한이 지났는가(기한 없음 false)
 * @param claimType  클레임 유형(클레임 접수만 · 그 외 null · D-250)
 * @param suggestion 클레임 처리 제안(클레임 접수만 · 그 외 null · D-250)
 */
public record InboxItemResponse(
        InboxItemType type,
        String ref,
        String title,
        String subtitle,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime baseAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime dueAt,
        boolean overdue,
        String targetKey,
        ClaimType claimType,
        ClaimSuggestion suggestion) {

    /** claimType·suggestion은 클레임 접수 행만 채운다(그 외 유형·조회 사이 처리된 클레임은 null). */
    public static InboxItemResponse of(InboxRow row, LocalDateTime now, ClaimType claimType, ClaimSuggestion suggestion) {
        boolean overdue = row.dueAt() != null && row.dueAt().isBefore(now);
        return new InboxItemResponse(row.type(), row.ref(), row.title(), row.subtitle(), row.baseAt(), row.dueAt(), overdue,
                row.type().targetKey(), claimType, suggestion);
    }
}
