package com.zslab.mall.answerdraft.service;

import com.zslab.mall.inquiry.enums.InquiryCategory;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * 규칙·템플릿 답안 초안(D-253). 템플릿은 코드 상수 — 문의 카테고리 5종별 1개 + 상품 Q&A 1개이며 인사 → 근거 요약 → 마무리로 조립한다. 근거 요약은
 * 근거마다 초안용 문장 1줄({@link AnswerEvidence#draftText} · 서비스가 문장 경계로 자른 값 · W15)만 싣는다 — 근거 최대 6건 × 120자에 템플릿 문구를
 * 더해도 답변 한도(1000자) 안에 든다.
 */
@Component
public class TemplateAnswerDraftPolicy implements AnswerDraftPolicy {

    private static final String SECTION_SEPARATOR = "\n\n";
    private static final String EVIDENCE_BULLET = "- ";

    private static final Template QUESTION_TEMPLATE = new Template(
            "안녕하세요, 고객님. 상품에 관심 가져 주셔서 감사합니다.",
            "이전 답변과 안내 내용을 바탕으로 말씀드립니다.",
            "더 궁금하신 점은 언제든 질문 남겨 주세요. 감사합니다.");

    @Override
    public Optional<String> draft(AnswerDraftInput input) {
        if (input.evidence().isEmpty()) {
            return Optional.empty();
        }
        Template template = input.category() == null ? QUESTION_TEMPLATE : templateOf(input.category());
        StringJoiner summaries = new StringJoiner("\n");
        for (AnswerEvidence evidence : input.evidence()) {
            summaries.add(EVIDENCE_BULLET + evidence.draftText());
        }
        return Optional.of(template.greeting() + SECTION_SEPARATOR + template.intro() + "\n" + summaries + SECTION_SEPARATOR
                + template.closing());
    }

    /** switch 식이라 카테고리가 늘면 컴파일이 멈춘다(템플릿 누락 방지). */
    private static Template templateOf(InquiryCategory category) {
        return switch (category) {
            case ORDER_PAYMENT -> new Template(
                    "안녕하세요, 고객님. 주문·결제 관련 문의 주셔서 감사합니다.",
                    "확인한 내용을 안내해 드립니다.",
                    "주문·결제 내역은 마이페이지 주문 내역에서도 확인하실 수 있습니다. 추가로 궁금하신 점은 언제든 문의해 주세요.");
            case DELIVERY -> new Template(
                    "안녕하세요, 고객님. 배송 관련 문의 주셔서 감사합니다.",
                    "확인한 배송 관련 내용을 안내해 드립니다.",
                    "배송 진행 상황은 마이페이지 주문 상세에서도 확인하실 수 있습니다. 추가로 궁금하신 점은 언제든 문의해 주세요.");
            case CLAIM -> new Template(
                    "안녕하세요, 고객님. 취소·반품·교환 관련 문의 주셔서 감사합니다.",
                    "확인한 내용과 절차를 안내해 드립니다.",
                    "취소·반품·교환 신청은 마이페이지 주문 상세에서 하실 수 있습니다. 추가로 궁금하신 점은 언제든 문의해 주세요.");
            case ACCOUNT -> new Template(
                    "안녕하세요, 고객님. 회원·계정 관련 문의 주셔서 감사합니다.",
                    "확인한 내용을 안내해 드립니다.",
                    "회원 정보는 마이페이지에서 확인하실 수 있습니다. 추가로 궁금하신 점은 언제든 문의해 주세요.");
            case OTHER -> new Template(
                    "안녕하세요, 고객님. 문의 주셔서 감사합니다.",
                    "확인한 내용을 안내해 드립니다.",
                    "추가로 궁금하신 점은 언제든 문의해 주세요. 감사합니다.");
        };
    }

    private record Template(String greeting, String intro, String closing) {
    }
}
