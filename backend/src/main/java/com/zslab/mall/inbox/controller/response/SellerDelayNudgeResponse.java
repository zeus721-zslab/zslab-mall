package com.zslab.mall.inbox.controller.response;

import com.zslab.mall.inbox.enums.SellerNudgeResult;
import java.util.List;

/**
 * 셀러 지연 일괄 독촉 결과(D-252). 셀러별 결과가 무엇이든 200이며 입력 순서대로 담는다.
 */
public record SellerDelayNudgeResponse(List<Item> results, int sentCount, int failedCount, int noRecipientCount,
        int cooldownCount, int noDelayCount) {

    public static SellerDelayNudgeResponse of(List<Item> results) {
        return new SellerDelayNudgeResponse(results, count(results, SellerNudgeResult.SENT),
                count(results, SellerNudgeResult.FAILED), count(results, SellerNudgeResult.NO_RECIPIENT),
                count(results, SellerNudgeResult.COOLDOWN), count(results, SellerNudgeResult.NO_DELAY));
    }

    private static int count(List<Item> results, SellerNudgeResult result) {
        return (int) results.stream().filter(item -> item.result() == result).count();
    }

    public record Item(String sellerPublicId, SellerNudgeResult result) {
    }
}
