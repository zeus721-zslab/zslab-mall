package com.zslab.mall.claim.controller.response;

import java.util.List;

/**
 * 관리자 클레임 일괄 승인 결과(D-250). 항목 실패가 있어도 200이며 입력 순서대로 결과를 담는다.
 *
 * @param results 항목별 결과(성공은 code·message null)
 */
public record AdminClaimBulkApproveResponse(List<Item> results, int successCount, int failureCount) {

    public static AdminClaimBulkApproveResponse of(List<Item> results) {
        int successCount = (int) results.stream().filter(Item::success).count();
        return new AdminClaimBulkApproveResponse(results, successCount, results.size() - successCount);
    }

    public record Item(String claimPublicId, boolean success, String code, String message) {
    }
}
