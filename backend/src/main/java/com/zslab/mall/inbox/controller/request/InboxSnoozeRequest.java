package com.zslab.mall.inbox.controller.request;

import com.zslab.mall.inbox.entity.InboxSnooze;
import com.zslab.mall.inbox.enums.InboxItemType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * 인박스 보류 요청(D-248). type은 enum 바인딩으로 형식을 검증한다(허용 외 값 400). 대상이 지금 본인 인박스의 대기 항목인지는 서비스가 판정한다(404).
 *
 * @param ref     인박스 항목의 ref 그대로
 * @param untilAt 이 시각 전까지 숨김(ISO offset·미래만·상한 없음)
 * @param reason  보류 사유(1~200자·공백만 불가)
 */
public record InboxSnoozeRequest(
        @NotNull InboxItemType type,
        @NotBlank @Size(max = InboxSnooze.MAX_REF_LENGTH) String ref,
        @NotNull @Future OffsetDateTime untilAt,
        @NotBlank @Size(max = InboxSnooze.MAX_REASON_LENGTH) String reason) {
}
