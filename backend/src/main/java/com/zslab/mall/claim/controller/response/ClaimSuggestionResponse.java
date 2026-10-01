package com.zslab.mall.claim.controller.response;

import com.zslab.mall.claim.enums.ClaimSuggestion;
import com.zslab.mall.claim.enums.ClaimSuggestionRule;
import com.zslab.mall.claim.service.ClaimSuggestionResult;

/**
 * 클레임 처리 제안(D-250).
 *
 * @param ruleKey 일치한 규칙(R0~R5)
 * @param reason  근거 문구
 */
public record ClaimSuggestionResponse(ClaimSuggestion suggestion, ClaimSuggestionRule ruleKey, String reason) {

    public static ClaimSuggestionResponse from(ClaimSuggestionResult result) {
        return new ClaimSuggestionResponse(result.suggestion(), result.rule(), result.rule().reason());
    }
}
