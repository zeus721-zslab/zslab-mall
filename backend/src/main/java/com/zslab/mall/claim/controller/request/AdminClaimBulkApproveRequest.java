package com.zslab.mall.claim.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 관리자 클레임 일괄 승인 요청(D-250). 입력 순서대로 처리하며 중복을 걸러내지 않는다(같은 id 두 번째는 상태 422 항목 실패).
 */
public record AdminClaimBulkApproveRequest(
        @NotEmpty @Size(max = AdminClaimBulkApproveRequest.MAX_ITEMS) List<@NotBlank String> claimPublicIds) {

    /** 건수 상한 — 취소 1건마다 커밋 후 환불 체인이 요청 스레드에서 동기 실행되므로 작게 둔다(D-250 · 건당 소요 미측정 · 재검토 이월). */
    public static final int MAX_ITEMS = 20;
}
