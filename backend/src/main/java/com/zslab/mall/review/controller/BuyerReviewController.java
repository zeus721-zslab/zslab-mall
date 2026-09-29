package com.zslab.mall.review.controller;

import com.zslab.mall.common.auth.BuyerActorResolver;
import com.zslab.mall.review.controller.request.ReviewCreateRequest;
import com.zslab.mall.review.controller.request.ReviewUpdateRequest;
import com.zslab.mall.review.controller.response.ReviewAttachmentUploadResponse;
import com.zslab.mall.review.controller.response.ReviewCreatedResponse;
import com.zslab.mall.review.controller.response.ReviewHelpfulResponse;
import com.zslab.mall.review.controller.response.ReviewOwnerResponse;
import com.zslab.mall.review.entity.Review;
import com.zslab.mall.review.service.ReviewAttachmentService;
import com.zslab.mall.review.service.ReviewHelpfulService;
import com.zslab.mall.review.service.ReviewQueryService;
import com.zslab.mall.review.service.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 구매자 리뷰 쓰기 REST 컨트롤러(Track 106-1). 인가는 SecurityConfig {@code /api/v1/reviews/**}→BUYER. HTTP 책임만 가진다(액터 해석·
 * Service 위임·HTTP 변환) — 자격·소유권·키워드 소속은 {@link ReviewService}가 판정한다.
 */
@RestController
@RequestMapping("/api/v1/reviews")
public class BuyerReviewController {

    private final ReviewService reviewService;
    private final ReviewAttachmentService reviewAttachmentService;
    private final ReviewHelpfulService reviewHelpfulService;
    private final ReviewQueryService reviewQueryService;
    private final BuyerActorResolver buyerActorResolver;

    public BuyerReviewController(ReviewService reviewService, ReviewAttachmentService reviewAttachmentService,
            ReviewHelpfulService reviewHelpfulService, ReviewQueryService reviewQueryService, BuyerActorResolver buyerActorResolver) {
        this.reviewService = reviewService;
        this.reviewAttachmentService = reviewAttachmentService;
        this.reviewHelpfulService = reviewHelpfulService;
        this.reviewQueryService = reviewQueryService;
        this.buyerActorResolver = buyerActorResolver;
    }

    /**
     * 리뷰 작성. 201 + reviewId. 타인·미존재 품목 404 · 구매확정 전 422 · 이미 작성(삭제 포함) 409 · 형식·키워드 400.
     */
    @PostMapping
    public ResponseEntity<ReviewCreatedResponse> create(
            @RequestBody @Valid ReviewCreateRequest request, HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        Review review = reviewService.create(buyerId, request.orderItemId(), request.rating(), request.keywordCodesOrEmpty(),
                request.content(), request.attachmentIdsOrEmpty());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ReviewCreatedResponse(review.getPublicId()));
    }

    /** 작성자 본인 리뷰 단건(수정 화면 초기값·숨김 상태와 사유 포함). 타인·미존재·삭제 404. */
    @GetMapping("/{reviewPublicId}")
    public ResponseEntity<ReviewOwnerResponse> getOwn(@PathVariable String reviewPublicId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(reviewQueryService.getOwn(buyerActorResolver.resolve(httpRequest), reviewPublicId));
    }

    /** 리뷰 수정(전체 교체). 204. 타인·미존재·삭제 404 · 숨김 422 · 형식·키워드·사진 400. */
    @PutMapping("/{reviewPublicId}")
    public ResponseEntity<Void> update(@PathVariable String reviewPublicId, @RequestBody @Valid ReviewUpdateRequest request,
            HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        reviewService.update(buyerId, reviewPublicId, request.rating(), request.keywordCodesOrEmpty(), request.content(),
                request.attachmentIdsOrEmpty());
        return ResponseEntity.noContent().build();
    }

    /** 리뷰 삭제(soft delete·재작성 불가). 204. 타인·미존재·이미 삭제 404. */
    @DeleteMapping("/{reviewPublicId}")
    public ResponseEntity<Void> delete(@PathVariable String reviewPublicId, HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        reviewService.delete(buyerId, reviewPublicId);
        return ResponseEntity.noContent().build();
    }

    /** 도움됐어요 추가(멱등). 200 {helped, helpfulCount}. 숨김·삭제·미존재 404 · 본인 리뷰 422. */
    @PostMapping("/{reviewPublicId}/helpful")
    public ResponseEntity<ReviewHelpfulResponse> addHelpful(@PathVariable String reviewPublicId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(reviewHelpfulService.add(buyerActorResolver.resolve(httpRequest), reviewPublicId));
    }

    /** 도움됐어요 취소(멱등). 200 {helped, helpfulCount}. 숨김·삭제·미존재 404 · 본인 리뷰 422. */
    @DeleteMapping("/{reviewPublicId}/helpful")
    public ResponseEntity<ReviewHelpfulResponse> removeHelpful(@PathVariable String reviewPublicId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(reviewHelpfulService.remove(buyerActorResolver.resolve(httpRequest), reviewPublicId));
    }

    /**
     * 리뷰 사진 업로드. multipart 필드명 {@code files}(1장·jpg/png·파일당 5MB). 항상 200·파일별 결과이며 성공 항목의 attachmentId를 작성·수정
     * 본문 attachmentIds에 넘긴다. 요청 합계 상한은 {@code ReviewAttachmentRequestSizeFilter}(413/411).
     */
    @PostMapping(value = "/attachments", consumes = "multipart/form-data")
    public ResponseEntity<ReviewAttachmentUploadResponse> uploadAttachments(
            @RequestPart("files") List<MultipartFile> files, HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        return ResponseEntity.ok(reviewAttachmentService.upload(buyerId, files));
    }
}
