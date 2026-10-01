package com.zslab.mall.answerdraft.service;

import com.zslab.mall.answerdraft.controller.response.AnswerDraftResponse;
import com.zslab.mall.answerdraft.enums.AnswerEvidenceKind;
import com.zslab.mall.common.util.KeywordMatcher;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.faq.entity.Faq;
import com.zslab.mall.faq.service.FaqQueryService;
import com.zslab.mall.inquiry.entity.Inquiry;
import com.zslab.mall.inquiry.exception.InquiryNotFoundException;
import com.zslab.mall.inquiry.repository.InquiryRepository;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.repository.OrderNoStatusProjection;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.productquestion.entity.ProductQuestion;
import com.zslab.mall.productquestion.enums.ProductQuestionStatus;
import com.zslab.mall.productquestion.exception.ProductQuestionNotFoundException;
import com.zslab.mall.productquestion.repository.ProductQuestionRepository;
import com.zslab.mall.productquestion.service.ProductQuestionSuggestService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 답안 초안 근거 수집 + 초안 조립(D-253 · C3). 초안 GET 호출 시마다 계산하며 저장·감사하지 않는다. 근거는 개인정보 필드가 없는
 * {@link AnswerEvidence}로만 만들어 {@link AnswerDraftPolicy}에 넘긴다 — 주문은 주문번호·주문 상태·원 발송 배송 상태만 스칼라로 읽는다(배송지 미조회).
 */
@Service
@Transactional(readOnly = true)
public class AnswerDraftService {

    /** 근거 종류별 상한(FAQ·답변된 질문 각각). */
    static final int EVIDENCE_LIMIT_PER_KIND = 3;
    private static final String ORDER_TITLE_PREFIX = "주문 ";
    private static final String NO_DELIVERY = "배송 정보 없음";

    private final InquiryRepository inquiryRepository;
    private final OrderRepository orderRepository;
    private final DeliveryRepository deliveryRepository;
    private final ProductQuestionRepository productQuestionRepository;
    private final ProductRepository productRepository;
    private final FaqQueryService faqQueryService;
    private final AnswerDraftPolicy answerDraftPolicy;

    public AnswerDraftService(InquiryRepository inquiryRepository, OrderRepository orderRepository, DeliveryRepository deliveryRepository,
            ProductQuestionRepository productQuestionRepository, ProductRepository productRepository, FaqQueryService faqQueryService,
            AnswerDraftPolicy answerDraftPolicy) {
        this.inquiryRepository = inquiryRepository;
        this.orderRepository = orderRepository;
        this.deliveryRepository = deliveryRepository;
        this.productQuestionRepository = productQuestionRepository;
        this.productRepository = productRepository;
        this.faqQueryService = faqQueryService;
        this.answerDraftPolicy = answerDraftPolicy;
    }

    /**
     * 1:1 문의 초안. 근거 = 첨부 주문 상태(있으면) + FAQ 일치 상위 3. FAQ 근거가 0건이면 FAQ 후보다.
     *
     * @throws InquiryNotFoundException 미존재·삭제(404)
     */
    public AnswerDraftResponse draftForInquiry(String inquiryPublicId) {
        Inquiry inquiry = inquiryRepository.findByPublicId(inquiryPublicId)
                .orElseThrow(() -> new InquiryNotFoundException("문의를 찾을 수 없습니다: " + inquiryPublicId));
        List<AnswerEvidence> evidence = new ArrayList<>();
        if (inquiry.getOrderId() != null) {
            orderEvidenceOf(inquiry.getOrderId()).ifPresent(evidence::add);
        }
        List<AnswerEvidence> faqEvidence = faqEvidenceOf(inquiry.getContent());
        evidence.addAll(faqEvidence);
        String draft = answerDraftPolicy.draft(new AnswerDraftInput(inquiry.getContent(), inquiry.getCategory(), evidence)).orElse(null);
        return new AnswerDraftResponse(draft, evidence, faqEvidence.isEmpty());
    }

    /**
     * 셀러 상품 질문 초안. 근거 = 같은 상품의 답변된 공개 질문 중 본문 키워드 일치 상위 3(자기 자신 제외) + FAQ 일치 상위 3. 소유·삭제 가드는 셀러
     * 답변과 같다(다른 셀러 상품·삭제 질문·삭제 상품 → 404).
     *
     * @throws ProductQuestionNotFoundException 미존재·삭제·다른 셀러 상품의 질문(404)
     */
    public AnswerDraftResponse draftForSellerQuestion(Long sellerId, String questionPublicId) {
        ProductQuestion question = productQuestionRepository.findByPublicId(questionPublicId)
                .orElseThrow(() -> new ProductQuestionNotFoundException("질문을 찾을 수 없습니다: " + questionPublicId));
        Long ownerSellerId = productRepository.findById(question.getProductId()).map(Product::getSellerId).orElse(null);
        if (!sellerId.equals(ownerSellerId)) {
            throw new ProductQuestionNotFoundException("질문을 찾을 수 없습니다: " + questionPublicId);
        }
        List<AnswerEvidence> evidence = new ArrayList<>(answeredQuestionEvidenceOf(question));
        evidence.addAll(faqEvidenceOf(question.getContent()));
        String draft = answerDraftPolicy.draft(new AnswerDraftInput(question.getContent(), null, evidence)).orElse(null);
        return new AnswerDraftResponse(draft, evidence, false);
    }

    /** 즉시 답(ProductQuestionSuggestService)과 같은 후보·점수(질문 + 답변 텍스트) · 동점이면 최신순(후보 조회 순서 유지). */
    private List<AnswerEvidence> answeredQuestionEvidenceOf(ProductQuestion question) {
        List<List<String>> tokenForms = KeywordMatcher.tokenize(question.getContent());
        if (tokenForms.isEmpty()) {
            return List.of();
        }
        return productQuestionRepository.findRecentAnswered(question.getProductId(), ProductQuestionStatus.VISIBLE,
                        PageRequest.of(0, ProductQuestionSuggestService.CANDIDATE_LIMIT)).stream()
                .filter(candidate -> !candidate.getId().equals(question.getId()))
                .map(candidate -> new ScoredQuestion(candidate, KeywordMatcher.score(
                        (candidate.getContent() + " " + candidate.getAnswerContent()).toLowerCase(Locale.ROOT), tokenForms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredQuestion::score).reversed())
                .limit(EVIDENCE_LIMIT_PER_KIND)
                .map(scored -> new AnswerEvidence(AnswerEvidenceKind.ANSWERED_QUESTION, KeywordMatcher.excerpt(scored.question().getContent()),
                        KeywordMatcher.excerpt(scored.question().getAnswerContent())))
                .toList();
    }

    private List<AnswerEvidence> faqEvidenceOf(String content) {
        List<AnswerEvidence> evidence = new ArrayList<>();
        for (Faq faq : faqQueryService.matchVisible(content, EVIDENCE_LIMIT_PER_KIND)) {
            evidence.add(new AnswerEvidence(AnswerEvidenceKind.FAQ, KeywordMatcher.excerpt(faq.getQuestion()),
                    KeywordMatcher.excerpt(faq.getAnswer())));
        }
        return evidence;
    }

    /** 주문번호·주문 상태·원 발송 배송 상태(중복 제거 · 품목 순). 주문이 지워졌으면 근거 없음. */
    private Optional<AnswerEvidence> orderEvidenceOf(Long orderId) {
        return orderRepository.findNoAndStatusById(orderId).map(order -> new AnswerEvidence(AnswerEvidenceKind.ORDER,
                ORDER_TITLE_PREFIX + order.getOrderNo(), orderSummaryOf(order, deliveryStatusesOf(orderId))));
    }

    private List<DeliveryStatus> deliveryStatusesOf(Long orderId) {
        return deliveryRepository.findOriginalStatusesByOrderId(orderId, DeliveryDirection.OUTBOUND).stream().distinct().toList();
    }

    private static String orderSummaryOf(OrderNoStatusProjection order, List<DeliveryStatus> deliveryStatuses) {
        String delivery = deliveryStatuses.isEmpty() ? NO_DELIVERY
                : "배송 상태: " + deliveryStatuses.stream().map(AnswerDraftService::deliveryLabelOf).collect(Collectors.joining(", "));
        return "주문 상태: " + orderLabelOf(order.getStatus()) + " · " + delivery;
    }

    /** 초안 문장용 한글 라벨(구매자 화면 FE order.ts ORDER_STATUS 라벨과 같은 문구 · switch 식이라 값이 늘면 컴파일이 멈춘다). */
    private static String orderLabelOf(OrderStatus status) {
        return switch (status) {
            case PENDING_PAYMENT -> "결제대기";
            case PAID -> "결제완료";
            case PREPARING -> "상품준비중";
            case SHIPPING -> "배송중";
            case DELIVERED -> "배송완료";
            case CONFIRMED -> "구매확정";
            case CANCELLED -> "취소";
            case PARTIAL_CANCEL -> "부분취소";
            case PAYMENT_EXPIRED -> "미결제 종료";
        };
    }

    private static String deliveryLabelOf(DeliveryStatus status) {
        return switch (status) {
            case READY -> "발송준비";
            case SHIPPING -> "배송중";
            case DELIVERED -> "배송완료";
        };
    }

    private record ScoredQuestion(ProductQuestion question, int score) {
    }
}
