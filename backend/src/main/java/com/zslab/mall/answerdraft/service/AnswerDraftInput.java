package com.zslab.mall.answerdraft.service;

import com.zslab.mall.inquiry.enums.InquiryCategory;
import java.util.List;

/**
 * 답안 초안 정책 입력(D-253). 작성자·주문 배송지 같은 개인정보 필드는 두지 않는다.
 *
 * @param questionContent 문의·질문 본문
 * @param category        문의 카테고리(상품 Q&A는 null)
 * @param evidence        서비스가 수집한 근거(비면 초안을 만들지 않는다)
 */
public record AnswerDraftInput(String questionContent, InquiryCategory category, List<AnswerEvidence> evidence) {
}
