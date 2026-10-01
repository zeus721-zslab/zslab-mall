package com.zslab.mall.inbox.controller.request;

import com.zslab.mall.inbox.entity.InboxSnooze;
import com.zslab.mall.inbox.enums.InboxItemType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 인박스 보류 해제 대상(쿼리 파라미터 {@code type}·{@code ref}). 형식 위반 400. 본인 보류가 없으면 서비스가 아무 일도 하지 않는다.
 */
public record InboxSnoozeKeyRequest(
        @NotNull InboxItemType type,
        @NotBlank @Size(max = InboxSnooze.MAX_REF_LENGTH) String ref) {
}
