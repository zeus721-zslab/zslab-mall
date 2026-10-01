package com.zslab.mall.answerdraft.controller.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zslab.mall.answerdraft.service.AnswerEvidence;
import java.util.List;

/**
 * 답안 초안 응답(D-253 · 조회 시 계산 · 저장 없음).
 *
 * @param draft        초안 문장(근거가 없으면 null — 전역 non_null 설정과 달리 키를 남겨 "근거 부족"을 명시한다)
 * @param evidence     근거 목록(초안에 쓴 순서)
 * @param faqCandidate FAQ 후보 여부(1:1 문의에서 FAQ 근거가 0건이면 true · 상품 Q&A는 항상 false)
 */
public record AnswerDraftResponse(@JsonInclude(JsonInclude.Include.ALWAYS) String draft, List<AnswerEvidence> evidence,
        boolean faqCandidate) {
}
