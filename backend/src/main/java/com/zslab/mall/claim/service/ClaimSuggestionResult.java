package com.zslab.mall.claim.service;

import com.zslab.mall.claim.enums.ClaimSuggestion;
import com.zslab.mall.claim.enums.ClaimSuggestionRule;

/** 클레임 1건의 제안 판정 결과와 그 입력. */
public record ClaimSuggestionResult(ClaimSuggestionRule rule, ClaimSuggestionInput input) {

    public ClaimSuggestion suggestion() {
        return rule.suggestion();
    }
}
