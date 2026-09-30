package com.zslab.mall.productquestion.service;

import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.productquestion.controller.response.MyProductQuestionResponse;
import com.zslab.mall.productquestion.controller.response.ProductQuestionItemResponse;
import com.zslab.mall.productquestion.entity.ProductQuestion;
import com.zslab.mall.productquestion.enums.ProductQuestionStatus;
import com.zslab.mall.productquestion.repository.ProductQuestionRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 질문 조회(Track 106-2): 상품별 공개 목록·내 질문. 공개 = status VISIBLE + 미삭제(엔티티 @SQLRestriction)이고 미답변도 보여준다.
 * 페이징은 리뷰 기준(기본 10·최대 50)이다.
 */
@Service
@Transactional(readOnly = true)
public class ProductQuestionQueryService {

    static final int DEFAULT_PAGE_SIZE = 10;
    static final int MAX_PAGE_SIZE = 50;
    static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ProductQuestionRepository productQuestionRepository;
    private final ProductRepository productRepository;
    private final ExposedProductReader exposedProductReader;

    public ProductQuestionQueryService(ProductQuestionRepository productQuestionRepository, ProductRepository productRepository,
            ExposedProductReader exposedProductReader) {
        this.productQuestionRepository = productQuestionRepository;
        this.productRepository = productRepository;
        this.exposedProductReader = exposedProductReader;
    }

    /**
     * 상품 공개 질문 목록(최신순·답변 포함·미답변 포함).
     *
     * @param viewerId 로그인 구매자 id(익명이면 null — 항목의 writtenByMe를 채우지 않는다)
     * @throws ProductNotFoundException 상품 미존재·삭제·비노출(404)
     */
    public PagedResponse<ProductQuestionItemResponse> listPublic(String productPublicId, int page, int size, Long viewerId) {
        Product product = exposedProductReader.require(productPublicId);
        Page<ProductQuestion> questions = productQuestionRepository.findPublicPage(product.getId(), ProductQuestionStatus.VISIBLE,
                PageRequest.of(Math.max(page, 0), clampSize(size), LATEST));
        return PagedResponse.from(questions.map(question -> new ProductQuestionItemResponse(
                question.getPublicId(),
                question.getContent(),
                question.getAnswerContent(),
                question.getAnsweredAt(),
                viewerId != null ? question.isWrittenBy(viewerId) : null,
                question.getCreatedAt())));
    }

    /** 내 질문 목록(숨김 포함·삭제 제외·최신순·답변 포함). 페이지 질문의 상품은 한 번에 배치 조회한다. */
    public PagedResponse<MyProductQuestionResponse> listMine(Long buyerId, int page, int size) {
        Page<ProductQuestion> questions = productQuestionRepository.findByBuyerId(buyerId,
                PageRequest.of(Math.max(page, 0), clampSize(size), LATEST));
        Map<Long, Product> products = productsById(questions.getContent().stream().map(ProductQuestion::getProductId).toList());
        return PagedResponse.from(questions.map(question -> {
            Product product = products.get(question.getProductId());
            return new MyProductQuestionResponse(
                    question.getPublicId(),
                    product == null ? null : product.getPublicId(),
                    product == null ? null : product.getName(),
                    question.getContent(),
                    question.getStatus(),
                    question.getHiddenReason(),
                    question.getAnswerContent(),
                    question.getAnsweredAt(),
                    !question.isAnswered() && question.getStatus() == ProductQuestionStatus.VISIBLE,
                    !question.isAnswered(),
                    question.getCreatedAt());
        }));
    }

    /** 페이지 질문의 상품(삭제 상품은 맵에 없음). */
    private Map<Long, Product> productsById(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productRepository.findByIdIn(productIds.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    static int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
