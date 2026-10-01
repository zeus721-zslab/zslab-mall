package com.zslab.mall.answerdraft.service;

import java.util.Optional;

/**
 * 답안 초안 생성(D-253). 규칙·템플릿 구현 1개를 두며, LLM으로 바꿀 때 이 지점만 교체한다(C3 · D-220).
 */
public interface AnswerDraftPolicy {

    /** @return 초안 문장(근거가 없으면 empty) */
    Optional<String> draft(AnswerDraftInput input);
}
