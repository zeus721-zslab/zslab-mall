package com.zslab.mall.inquiry.controller;

import com.zslab.mall.common.auth.BuyerActorResolver;
import com.zslab.mall.inquiry.controller.request.InquiryCreateRequest;
import com.zslab.mall.inquiry.controller.request.InquiryUpdateRequest;
import com.zslab.mall.inquiry.controller.response.InquiryCreatedResponse;
import com.zslab.mall.inquiry.controller.response.MyInquiryResponse;
import com.zslab.mall.inquiry.entity.Inquiry;
import com.zslab.mall.inquiry.enums.InquiryCategory;
import com.zslab.mall.inquiry.service.InquiryQueryService;
import com.zslab.mall.inquiry.service.InquiryService;
import com.zslab.mall.order.controller.response.PagedResponse;
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
 * 구매자 운영자 문의 REST 컨트롤러(Track 106-4). 인가는 SecurityConfig {@code /api/v1/inquiries/**}→BUYER. HTTP 책임만 가진다 — 주문 소유·
 * 문의 소유·상태 규칙은 {@link InquiryService}가 판정한다.
 */
@RestController
@RequestMapping("/api/v1/inquiries")
public class BuyerInquiryController {

    private final InquiryService inquiryService;
    private final InquiryQueryService inquiryQueryService;
    private final BuyerActorResolver buyerActorResolver;

    public BuyerInquiryController(InquiryService inquiryService, InquiryQueryService inquiryQueryService,
            BuyerActorResolver buyerActorResolver) {
        this.inquiryService = inquiryService;
        this.inquiryQueryService = inquiryQueryService;
        this.buyerActorResolver = buyerActorResolver;
    }

    /** 문의 등록. 201 + inquiryId · 첨부 주문 미존재·타인 404 · 형식 400. */
    @PostMapping
    public ResponseEntity<InquiryCreatedResponse> create(@RequestBody @Valid InquiryCreateRequest request, HttpServletRequest httpRequest) {
        Inquiry inquiry = inquiryService.create(buyerActorResolver.resolve(httpRequest), InquiryCategory.valueOf(request.category()),
                request.content(), request.orderId());
        return ResponseEntity.status(HttpStatus.CREATED).body(new InquiryCreatedResponse(inquiry.getPublicId()));
    }

    /** 문의 수정(카테고리·본문). 204 · 타인·미존재·삭제 404 · 답변 완료 422 · 형식 400. */
    @PutMapping("/{inquiryPublicId}")
    public ResponseEntity<Void> update(@PathVariable String inquiryPublicId, @RequestBody @Valid InquiryUpdateRequest request,
            HttpServletRequest httpRequest) {
        inquiryService.update(buyerActorResolver.resolve(httpRequest), inquiryPublicId, InquiryCategory.valueOf(request.category()),
                request.content());
        return ResponseEntity.noContent().build();
    }

    /** 문의 삭제(soft delete). 204 · 타인·미존재·이미 삭제 404 · 답변 완료 422. */
    @DeleteMapping("/{inquiryPublicId}")
    public ResponseEntity<Void> delete(@PathVariable String inquiryPublicId, HttpServletRequest httpRequest) {
        inquiryService.delete(buyerActorResolver.resolve(httpRequest), inquiryPublicId);
        return ResponseEntity.noContent().build();
    }

    /** 답변 확인(미확인 해제·멱등). 204 · 타인·미존재·삭제 404 · 미답변 422. */
    @PutMapping("/{inquiryPublicId}/answer-check")
    public ResponseEntity<Void> checkAnswer(@PathVariable String inquiryPublicId, HttpServletRequest httpRequest) {
        inquiryService.checkAnswer(buyerActorResolver.resolve(httpRequest), inquiryPublicId);
        return ResponseEntity.noContent().build();
    }

    /** 내 문의 목록(최신순·답변 포함). page 0부터 · size 기본 10(최대 50). */
    @GetMapping("/me")
    public ResponseEntity<PagedResponse<MyInquiryResponse>> listMine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(inquiryQueryService.listMine(buyerActorResolver.resolve(httpRequest), page, size));
    }
}
