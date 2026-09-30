package com.zslab.mall.productquestion.controller;

import com.zslab.mall.common.auth.BuyerActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.productquestion.controller.response.ProductQuestionItemResponse;
import com.zslab.mall.productquestion.controller.response.ProductQuestionSuggestionResponse;
import com.zslab.mall.productquestion.service.ProductQuestionQueryService;
import com.zslab.mall.productquestion.service.ProductQuestionSuggestService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 질문 공개 조회(Track 106-2). 인가는 SecurityConfig GET {@code /api/v1/products/**} permitAll(비로그인 가능). 구매자 쿠키가 있으면 필터가
 * 인증하므로 목록 항목에 "내 질문인지"를 붙인다.
 */
@RestController
@RequestMapping("/api/v1/products/{productPublicId}/questions")
public class ProductQuestionController {

    private final ProductQuestionQueryService productQuestionQueryService;
    private final ProductQuestionSuggestService productQuestionSuggestService;
    private final BuyerActorResolver buyerActorResolver;

    public ProductQuestionController(ProductQuestionQueryService productQuestionQueryService,
            ProductQuestionSuggestService productQuestionSuggestService, BuyerActorResolver buyerActorResolver) {
        this.productQuestionQueryService = productQuestionQueryService;
        this.productQuestionSuggestService = productQuestionSuggestService;
        this.buyerActorResolver = buyerActorResolver;
    }

    /** 공개 질문 목록(최신순·답변 포함·미답변 포함). page 0부터 · size 기본 10(최대 50) · 비노출 상품 404. */
    @GetMapping
    public ResponseEntity<PagedResponse<ProductQuestionItemResponse>> list(
            @PathVariable String productPublicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(productQuestionQueryService.listPublic(productPublicId, page, size,
                buyerActorResolver.find().orElse(null)));
    }

    /** 입력 중 즉시 답(상위 5건·없으면 빈 배열). q trim 후 2~100자(범위 밖·누락 400) · 비노출 상품 404. */
    @GetMapping("/suggest")
    public ResponseEntity<List<ProductQuestionSuggestionResponse>> suggest(
            @PathVariable String productPublicId, @RequestParam String q) {
        return ResponseEntity.ok(productQuestionSuggestService.suggest(productPublicId, q));
    }
}
