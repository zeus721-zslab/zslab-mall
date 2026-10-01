package com.zslab.mall.productquestion.service;

import com.zslab.mall.inbox.stream.InboxSignalPublisher;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.productquestion.controller.response.SellerProductQuestionResponse;
import com.zslab.mall.productquestion.entity.ProductQuestion;
import com.zslab.mall.productquestion.enums.ProductQuestionAnsweredFilter;
import com.zslab.mall.productquestion.enums.ProductQuestionStatus;
import com.zslab.mall.productquestion.exception.ProductQuestionInvalidStateException;
import com.zslab.mall.productquestion.exception.ProductQuestionNotFoundException;
import com.zslab.mall.productquestion.repository.ProductQuestionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 상품 질문(Track 106-2): 자기 상품의 질문 목록·답변 등록·수정. 소유 대조는 질문의 상품 sellerId와 요청 셀러를 비교하고, 다른 셀러 상품의
 * 질문은 미존재와 같은 404로 은닉한다(셀러 상품 {@code findOwnedForUpdate} 선례). 목록은 공개(VISIBLE) 질문만 보여준다 — 숨김 질문에는 답할 수
 * 없기 때문이다.
 */
@Slf4j
@Service
@Transactional
public class SellerProductQuestionService {

    /** 미답변부터 처리하도록 오래된 순. */
    private static final Sort OLDEST = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

    private final ProductQuestionRepository productQuestionRepository;
    private final ProductRepository productRepository;
    private final InboxSignalPublisher inboxSignalPublisher;

    public SellerProductQuestionService(ProductQuestionRepository productQuestionRepository, ProductRepository productRepository,
            InboxSignalPublisher inboxSignalPublisher) {
        this.productQuestionRepository = productQuestionRepository;
        this.productRepository = productRepository;
        this.inboxSignalPublisher = inboxSignalPublisher;
    }

    /** 자기 상품의 공개 질문 목록(답변 여부 필터·오래된 순). 페이지 질문의 상품은 한 번에 배치 조회한다. */
    @Transactional(readOnly = true)
    public PagedResponse<SellerProductQuestionResponse> list(Long sellerId, ProductQuestionAnsweredFilter answered, int page, int size) {
        Page<ProductQuestion> questions = productQuestionRepository.findForSeller(sellerId, ProductQuestionStatus.VISIBLE,
                answered.answeredCondition(), PageRequest.of(Math.max(page, 0), ProductQuestionQueryService.clampSize(size), OLDEST));
        List<ProductQuestion> content = questions.getContent();
        Map<Long, Product> products = content.isEmpty() ? Map.of()
                : productRepository.findByIdIn(content.stream().map(ProductQuestion::getProductId).distinct().toList()).stream()
                        .collect(Collectors.toMap(Product::getId, Function.identity()));
        return PagedResponse.from(questions.map(question -> {
            Product product = products.get(question.getProductId());
            return new SellerProductQuestionResponse(
                    question.getPublicId(),
                    product == null ? null : product.getPublicId(),
                    product == null ? null : product.getName(),
                    question.getContent(),
                    question.getAnswerContent(),
                    question.getAnsweredAt(),
                    question.getCreatedAt());
        }));
    }

    /**
     * 답변 등록·수정(덮어쓰기). 질문 행 락으로 질문자 수정·삭제·관리자 숨김과 직렬화한다 — 삭제가 먼저 커밋되면 락 해제 뒤 조회에서 빠져 404다.
     *
     * @param answererUserId 답변한 셀러 소속 User.id(answered_by)
     * @throws ProductQuestionNotFoundException 미존재·삭제·다른 셀러 상품의 질문(404)
     * @throws ProductQuestionInvalidStateException 숨김 질문(422)
     */
    public void answer(Long sellerId, Long answererUserId, String questionPublicId, String content) {
        ProductQuestion question = productQuestionRepository.findByPublicIdForUpdate(questionPublicId)
                .orElseThrow(() -> new ProductQuestionNotFoundException("질문을 찾을 수 없습니다: " + questionPublicId));
        Long ownerSellerId = productRepository.findById(question.getProductId()).map(Product::getSellerId).orElse(null);
        if (!sellerId.equals(ownerSellerId)) {
            throw new ProductQuestionNotFoundException("질문을 찾을 수 없습니다: " + questionPublicId);
        }
        try {
            question.answer(content.trim(), answererUserId, LocalDateTime.now());
        } catch (IllegalStateException exception) {
            throw new ProductQuestionInvalidStateException(exception.getMessage() + " questionPublicId=" + questionPublicId);
        }
        inboxSignalPublisher.sellerChanged(sellerId); // 셀러 Q&A 미답변 이탈
        log.info("[SellerProductQuestion] 답변 questionPublicId={} sellerId={} byUser={}", questionPublicId, sellerId, answererUserId);
    }
}
