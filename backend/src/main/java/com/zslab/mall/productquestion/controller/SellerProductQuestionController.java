package com.zslab.mall.productquestion.controller;

import com.zslab.mall.common.auth.AuthenticatedUserResolver;
import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.productquestion.controller.request.ProductQuestionAnswerRequest;
import com.zslab.mall.productquestion.controller.response.SellerProductQuestionResponse;
import com.zslab.mall.productquestion.enums.ProductQuestionAnsweredFilter;
import com.zslab.mall.productquestion.service.SellerProductQuestionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 상품 질문 REST 컨트롤러(Track 106-2). 인가는 SecurityConfig {@code /api/v1/seller/**}→SELLER. 셀러 id는
 * {@link SellerActorResolver}가 해석하며 정지(SUSPENDED) 셀러의 쓰기는 거기서 403이다. 답변자 User.id는 {@link AuthenticatedUserResolver}로 얻는다.
 */
@RestController
@RequestMapping("/api/v1/seller/product-questions")
public class SellerProductQuestionController {

    private final SellerProductQuestionService sellerProductQuestionService;
    private final SellerActorResolver sellerActorResolver;
    private final AuthenticatedUserResolver authenticatedUserResolver;

    public SellerProductQuestionController(SellerProductQuestionService sellerProductQuestionService,
            SellerActorResolver sellerActorResolver, AuthenticatedUserResolver authenticatedUserResolver) {
        this.sellerProductQuestionService = sellerProductQuestionService;
        this.sellerActorResolver = sellerActorResolver;
        this.authenticatedUserResolver = authenticatedUserResolver;
    }

    /** 자기 상품의 공개 질문 목록. answered ALL·UNANSWERED(기본)·ANSWERED(오값 400) · 오래된 순 · size 기본 10(최대 50). */
    @GetMapping
    public ResponseEntity<PagedResponse<SellerProductQuestionResponse>> list(
            @RequestParam(defaultValue = "UNANSWERED") ProductQuestionAnsweredFilter answered,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(sellerProductQuestionService.list(sellerActorResolver.resolve(httpRequest), answered, page, size));
    }

    /** 답변 등록·수정(덮어쓰기). 204 · 미존재·삭제·다른 셀러 404 · 숨김 422 · 형식 400 · 정지 셀러 403. */
    @PutMapping("/{questionPublicId}/answer")
    public ResponseEntity<Void> answer(@PathVariable String questionPublicId, @RequestBody @Valid ProductQuestionAnswerRequest request,
            HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        sellerProductQuestionService.answer(sellerId, authenticatedUserResolver.requireUserId(), questionPublicId, request.content());
        return ResponseEntity.noContent().build();
    }
}
