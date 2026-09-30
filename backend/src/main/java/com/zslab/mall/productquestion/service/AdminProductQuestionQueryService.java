package com.zslab.mall.productquestion.service;

import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.productquestion.controller.response.AdminProductQuestionResponse;
import com.zslab.mall.productquestion.entity.ProductQuestion;
import com.zslab.mall.productquestion.enums.ProductQuestionStatus;
import com.zslab.mall.productquestion.repository.ProductQuestionRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 질문 목록(Track 106-2·숨김 판단용). 상태 필터(없으면 전체)·최신순·삭제 제외. 페이지 질문의 상품은 한 번에 배치 조회한다.
 */
@Service
@Transactional(readOnly = true)
public class AdminProductQuestionQueryService {

    private final ProductQuestionRepository productQuestionRepository;
    private final ProductRepository productRepository;

    public AdminProductQuestionQueryService(ProductQuestionRepository productQuestionRepository, ProductRepository productRepository) {
        this.productQuestionRepository = productQuestionRepository;
        this.productRepository = productRepository;
    }

    /** @param status 상태 필터(없으면 null = 전체) */
    public PagedResponse<AdminProductQuestionResponse> list(ProductQuestionStatus status, int page, int size) {
        Page<ProductQuestion> questions = productQuestionRepository.findForAdmin(status,
                PageRequest.of(Math.max(page, 0), ProductQuestionQueryService.clampSize(size), ProductQuestionQueryService.LATEST));
        List<ProductQuestion> content = questions.getContent();
        Map<Long, Product> products = content.isEmpty() ? Map.of()
                : productRepository.findByIdIn(content.stream().map(ProductQuestion::getProductId).distinct().toList()).stream()
                        .collect(Collectors.toMap(Product::getId, Function.identity()));
        return PagedResponse.from(questions.map(question -> {
            Product product = products.get(question.getProductId());
            return new AdminProductQuestionResponse(
                    question.getPublicId(),
                    product == null ? null : product.getPublicId(),
                    product == null ? null : product.getName(),
                    question.getContent(),
                    question.getAnswerContent(),
                    question.getAnsweredAt(),
                    question.getStatus(),
                    question.getHiddenReason(),
                    question.getCreatedAt());
        }));
    }
}
