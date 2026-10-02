package com.zslab.mall.answerdraft.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zslab.mall.answerdraft.enums.AnswerEvidenceKind;

/**
 * 답안 초안 근거 1건(D-253). 초안 정책 입력과 응답에 같이 쓴다 — 개인정보 필드를 두지 않아 정책 구현(향후 LLM 포함)에 수령인·연락처·주소가 전달될
 * 경로가 없다(D-220 개인정보 미전송).
 *
 * @param title     근거 제목(FAQ 질문·답변된 질문 발췌·주문번호)
 * @param summary   근거 요약(FAQ 답·기존 답변 발췌·주문·배송 상태 · 화면 표시용 · 넘치면 말줄임표)
 * @param draftText 초안 본문에 넣을 문장(문장 경계 절단·말줄임표 없음 · 응답 미노출 · W15)
 */
public record AnswerEvidence(AnswerEvidenceKind kind, String title, String summary, @JsonIgnore String draftText) {
}
