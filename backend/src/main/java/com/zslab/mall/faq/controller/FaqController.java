package com.zslab.mall.faq.controller;

import com.zslab.mall.faq.controller.response.FaqResponse;
import com.zslab.mall.faq.service.FaqQueryService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구매자 채팅 도우미 FAQ 공개 조회(Track 106-3). 인가는 SecurityConfig의 두 경로 정확 매칭 GET permitAll(비로그인 가능).
 */
@RestController
@RequestMapping("/api/v1/faqs")
public class FaqController {

    private final FaqQueryService faqQueryService;

    public FaqController(FaqQueryService faqQueryService) {
        this.faqQueryService = faqQueryService;
    }

    /** 공개 FAQ 전체(카테고리 순서 → 카테고리 안 순서 · 1회 조회로 칩 탐색을 끝낸다). */
    @GetMapping
    public ResponseEntity<List<FaqResponse>> list() {
        return ResponseEntity.ok(faqQueryService.listVisible());
    }

    /** 자유 입력 즉시 답(상위 5건·없으면 빈 배열). q trim 후 2~100자(범위 밖·누락 400). */
    @GetMapping("/suggest")
    public ResponseEntity<List<FaqResponse>> suggest(@RequestParam String q) {
        return ResponseEntity.ok(faqQueryService.suggest(q));
    }
}
