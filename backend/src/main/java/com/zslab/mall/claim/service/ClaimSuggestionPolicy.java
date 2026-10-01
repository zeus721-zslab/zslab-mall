package com.zslab.mall.claim.service;

import com.zslab.mall.claim.enums.ClaimSuggestionRule;

/**
 * 클레임 처리 제안 판정(D-250). 규칙 구현 1개를 두며, LLM 보조로 바꿀 때 이 지점만 교체한다(C3 · D-220).
 */
public interface ClaimSuggestionPolicy {

    ClaimSuggestionRule evaluate(ClaimSuggestionInput input);
}
