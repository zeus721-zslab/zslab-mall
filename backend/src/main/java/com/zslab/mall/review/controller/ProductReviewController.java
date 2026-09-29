package com.zslab.mall.review.controller;

import com.zslab.mall.common.auth.BuyerActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.review.controller.response.ReviewItemResponse;
import com.zslab.mall.review.controller.response.ReviewKeywordOptionResponse;
import com.zslab.mall.review.controller.response.ReviewSummaryResponse;
import com.zslab.mall.review.enums.ReviewSort;
import com.zslab.mall.review.service.ReviewQueryService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 리뷰 공개 조회(Track 106-1). 인가는 SecurityConfig GET {@code /api/v1/products/**} permitAll(비로그인 가능). 구매자 쿠키가 있으면 필터가
 * 인증하므로 목록 항목에 "내가 도움됐어요 눌렀는지"를 붙인다. sort는 enum 직접 바인딩(오값 400 MALFORMED_REQUEST).
 */
@RestController
@RequestMapping("/api/v1/products/{productPublicId}/reviews")
public class ProductReviewController {

    private final ReviewQueryService reviewQueryService;
    private final BuyerActorResolver buyerActorResolver;

    public ProductReviewController(ReviewQueryService reviewQueryService, BuyerActorResolver buyerActorResolver) {
        this.reviewQueryService = reviewQueryService;
        this.buyerActorResolver = buyerActorResolver;
    }

    /** 공개 리뷰 목록. 필터 keyword(code)·option(작성 시점 옵션 라벨)·photoOnly · 정렬 LATEST(기본)·HELPFUL · page 0부터·size 기본 10(최대 50). */
    @GetMapping
    public ResponseEntity<PagedResponse<ReviewItemResponse>> list(
            @PathVariable String productPublicId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String option,
            @RequestParam(defaultValue = "false") boolean photoOnly,
            @RequestParam(defaultValue = "LATEST") ReviewSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(reviewQueryService.list(productPublicId, keyword, option, photoOnly, sort, page, size,
                buyerActorResolver.find().orElse(null)));
    }

    /** 리뷰 요약(별점 분포·키워드 집계·최근 사진·한 줄 요약). */
    @GetMapping("/summary")
    public ResponseEntity<ReviewSummaryResponse> summary(@PathVariable String productPublicId) {
        return ResponseEntity.ok(reviewQueryService.summary(productPublicId));
    }

    /** 작성 폼 키워드 선택지(기본 세트 ∪ 상품 최상위 카테고리 세트 · 표시 순서). */
    @GetMapping("/keywords")
    public ResponseEntity<List<ReviewKeywordOptionResponse>> keywords(@PathVariable String productPublicId) {
        return ResponseEntity.ok(reviewQueryService.usableKeywords(productPublicId));
    }
}
