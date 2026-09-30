package com.zslab.mall.productquestion.controller;

import com.zslab.mall.common.auth.BuyerActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.productquestion.controller.request.ProductQuestionCreateRequest;
import com.zslab.mall.productquestion.controller.request.ProductQuestionUpdateRequest;
import com.zslab.mall.productquestion.controller.response.MyProductQuestionResponse;
import com.zslab.mall.productquestion.controller.response.ProductQuestionCreatedResponse;
import com.zslab.mall.productquestion.entity.ProductQuestion;
import com.zslab.mall.productquestion.service.ProductQuestionQueryService;
import com.zslab.mall.productquestion.service.ProductQuestionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구매자 상품 질문 REST 컨트롤러(Track 106-2). 인가는 SecurityConfig {@code /api/v1/product-questions/**}→BUYER. HTTP 책임만 가진다 — 상품 노출·
 * 소유·상태 규칙은 {@link ProductQuestionService}가 판정한다.
 */
@RestController
@RequestMapping("/api/v1/product-questions")
public class BuyerProductQuestionController {

    private final ProductQuestionService productQuestionService;
    private final ProductQuestionQueryService productQuestionQueryService;
    private final BuyerActorResolver buyerActorResolver;

    public BuyerProductQuestionController(ProductQuestionService productQuestionService,
            ProductQuestionQueryService productQuestionQueryService, BuyerActorResolver buyerActorResolver) {
        this.productQuestionService = productQuestionService;
        this.productQuestionQueryService = productQuestionQueryService;
        this.buyerActorResolver = buyerActorResolver;
    }

    /** 질문 등록(항상 공개). 201 + questionId · 상품 미존재·비노출 404 · 형식 400. */
    @PostMapping
    public ResponseEntity<ProductQuestionCreatedResponse> create(
            @RequestBody @Valid ProductQuestionCreateRequest request, HttpServletRequest httpRequest) {
        ProductQuestion question = productQuestionService.create(buyerActorResolver.resolve(httpRequest), request.productId(),
                request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ProductQuestionCreatedResponse(question.getPublicId()));
    }

    /** 질문 수정. 204 · 타인·미존재·삭제 404 · 답변 완료·숨김 422 · 형식 400. */
    @PutMapping("/{questionPublicId}")
    public ResponseEntity<Void> update(@PathVariable String questionPublicId, @RequestBody @Valid ProductQuestionUpdateRequest request,
            HttpServletRequest httpRequest) {
        productQuestionService.update(buyerActorResolver.resolve(httpRequest), questionPublicId, request.content());
        return ResponseEntity.noContent().build();
    }

    /** 질문 삭제(soft delete). 204 · 타인·미존재·이미 삭제 404 · 답변 완료 422. */
    @DeleteMapping("/{questionPublicId}")
    public ResponseEntity<Void> delete(@PathVariable String questionPublicId, HttpServletRequest httpRequest) {
        productQuestionService.delete(buyerActorResolver.resolve(httpRequest), questionPublicId);
        return ResponseEntity.noContent().build();
    }

    /** 내 질문 목록(숨김 포함·최신순·답변 포함). page 0부터 · size 기본 10(최대 50). */
    @GetMapping("/me")
    public ResponseEntity<PagedResponse<MyProductQuestionResponse>> listMine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(productQuestionQueryService.listMine(buyerActorResolver.resolve(httpRequest), page, size));
    }
}
