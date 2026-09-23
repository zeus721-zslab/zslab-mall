package com.zslab.mall.reconciliation.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 불일치 해결 요청(Track 104-2 D-216). 메모는 필수이며 reconciliation_issue.resolution_memo(500)와 감사 로그에 남는다. */
public record AdminReconciliationIssueResolveRequest(
        @NotBlank @Size(max = 500) String memo) {
}
