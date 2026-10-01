package com.zslab.mall.productquestion.service;

import com.zslab.mall.inbox.stream.InboxSignalPublisher;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.productquestion.entity.ProductQuestion;
import com.zslab.mall.productquestion.exception.ProductQuestionInvalidStateException;
import com.zslab.mall.productquestion.exception.ProductQuestionNotFoundException;
import com.zslab.mall.productquestion.repository.ProductQuestionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구매자 상품 질문 쓰기(Track 106-2): 등록·수정·삭제. 질문은 항상 공개이고 구매 여부와 무관하게 로그인 구매자면 쓸 수 있다. 중복 제출 방어는
 * 서버에 두지 않는다(D-239). 수정·삭제는 질문 행 락으로 셀러 답변·관리자 숨김과 직렬화한다.
 */
@Slf4j
@Service
@Transactional
public class ProductQuestionService {

    private final ProductQuestionRepository productQuestionRepository;
    private final ExposedProductReader exposedProductReader;
    private final InboxSignalPublisher inboxSignalPublisher;

    public ProductQuestionService(ProductQuestionRepository productQuestionRepository, ExposedProductReader exposedProductReader,
            InboxSignalPublisher inboxSignalPublisher) {
        this.productQuestionRepository = productQuestionRepository;
        this.exposedProductReader = exposedProductReader;
        this.inboxSignalPublisher = inboxSignalPublisher;
    }

    /**
     * 질문을 등록한다. 본문은 trim해 저장한다(형식은 DTO가 trim 후 5~500자로 선검증).
     *
     * @throws ProductNotFoundException 상품 미존재·비노출(404 — 공개 조회와 같은 판정)
     */
    public ProductQuestion create(Long buyerId, String productPublicId, String content) {
        Product product = exposedProductReader.require(productPublicId);
        ProductQuestion question = productQuestionRepository.save(ProductQuestion.create(product.getId(), buyerId, content.trim()));
        log.info("[ProductQuestion] 등록 questionPublicId={} productId={} buyerId={}", question.getPublicId(), product.getId(), buyerId);
        inboxSignalPublisher.sellerChanged(product.getSellerId()); // 셀러 Q&A 미답변 진입
        return question;
    }

    /**
     * 질문자 수정(본문 교체). 미답변 + VISIBLE일 때만 가능하다.
     *
     * @throws ProductQuestionNotFoundException 미존재·삭제·타인 질문(404)
     * @throws ProductQuestionInvalidStateException 답변 완료·숨김 질문(422)
     */
    public void update(Long buyerId, String questionPublicId, String content) {
        ProductQuestion question = requireOwnQuestionForUpdate(buyerId, questionPublicId);
        try {
            question.edit(content.trim());
        } catch (IllegalStateException exception) {
            throw new ProductQuestionInvalidStateException(exception.getMessage() + " questionPublicId=" + questionPublicId);
        }
        log.info("[ProductQuestion] 수정 questionPublicId={} buyerId={}", questionPublicId, buyerId);
    }

    /**
     * 질문자 삭제(soft delete). 미답변일 때만 가능하다(숨김이어도 허용).
     *
     * @throws ProductQuestionNotFoundException 미존재·이미 삭제·타인 질문(404)
     * @throws ProductQuestionInvalidStateException 답변 완료 질문(422)
     */
    public void delete(Long buyerId, String questionPublicId) {
        ProductQuestion question = requireOwnQuestionForUpdate(buyerId, questionPublicId);
        try {
            question.deleteByAuthor();
        } catch (IllegalStateException exception) {
            throw new ProductQuestionInvalidStateException(exception.getMessage() + " questionPublicId=" + questionPublicId);
        }
        log.info("[ProductQuestion] 삭제 questionPublicId={} buyerId={}", questionPublicId, buyerId);
        // 셀러 Q&A 미답변 이탈 — 질문은 productId만 들고 있어 셀러 전체에 알린다.
        inboxSignalPublisher.allSellersChanged();
    }

    /** 락을 잡고 작성자 대조. 타인 질문도 미존재와 같은 404로 은닉한다. */
    private ProductQuestion requireOwnQuestionForUpdate(Long buyerId, String questionPublicId) {
        ProductQuestion question = productQuestionRepository.findByPublicIdForUpdate(questionPublicId)
                .orElseThrow(() -> new ProductQuestionNotFoundException("질문을 찾을 수 없습니다: " + questionPublicId));
        if (!question.isWrittenBy(buyerId)) {
            throw new ProductQuestionNotFoundException("질문을 찾을 수 없습니다: " + questionPublicId);
        }
        return question;
    }
}
