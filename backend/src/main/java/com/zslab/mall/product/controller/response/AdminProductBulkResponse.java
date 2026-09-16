package com.zslab.mall.product.controller.response;

import java.util.List;

/**
 * 일괄 변경 결과(Track 76). 항목별 독립 트랜잭션이라 부분 실패가 가능하며, 실패 항목은 code(기존 에러 코드·PRODUCT_NOT_FOUND·
 * PRODUCT_INVALID_STATE)와 message를 담는다. HTTP는 항상 200이고 성공/실패 집계는 본 응답으로 판단한다.
 */
public record AdminProductBulkResponse(List<Item> results, int successCount, int failureCount) {

    public record Item(String productPublicId, boolean success, String code, String message) {
    }

    public static AdminProductBulkResponse of(List<Item> results) {
        int successCount = (int) results.stream().filter(Item::success).count();
        return new AdminProductBulkResponse(results, successCount, results.size() - successCount);
    }
}
