package com.zslab.mall.review.service;

import com.zslab.mall.llm.adapter.LlmPort;
import com.zslab.mall.llm.adapter.ReviewSummaryPrompt;
import com.zslab.mall.review.controller.response.ReviewSummaryResponse;
import com.zslab.mall.review.enums.ReviewStatus;
import com.zslab.mall.review.repository.ProductReviewSummaryRepository;
import com.zslab.mall.review.repository.RatingCountProjection;
import com.zslab.mall.review.repository.ReviewRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 리뷰 한 줄 요약 재계산(Track 106-1·결정 6 — 커밋 후 비동기 저장). 공개 리뷰 전체를 다시 읽어 {@link LlmPort}로 문장을 만들고
 * product_review_summary에 덮어쓴다. 공개 리뷰가 0건이면 행을 지운다("요약 없음"). 호출은 전용 단일 스레드 실행기
 * ({@code ReviewSummaryHandler})에서만 하므로 같은 JVM의 재계산은 직렬이다.
 *
 * <p>한 번의 재계산은 여러 번 읽으므로(분포·키워드·최근 본문) REPEATABLE READ 한 트랜잭션의 스냅샷 하나로 계산한다 — 읽는 도중 커밋된 변경이
 * 일부 읽기에만 섞이지 않는다. 결과는 그 스냅샷 시점의 상태이지 "항상 최신"이 아니다: 스냅샷 뒤 커밋된 변경은 그 변경이 커밋 후 넣은 다음
 * 재계산이 반영하고, 그 재계산이 큐 초과·실패로 빠지면 다음 변경까지 오래된 요약이 남는다.
 *
 * <p>요약은 권위 데이터가 아니다(표시용 파생값 — 별점·개수·목록은 review에서 직접 집계한다). 큐 초과·LLM 실패 시 다음 변경까지 오래된
 * 요약을 허용한다.
 */
@Slf4j
@Service
@Transactional
public class ReviewSummaryService {

    /** 요약 입력에 넣는 많이 고른 키워드 수. */
    static final int TOP_KEYWORD_LIMIT = 2;
    /** 요약 입력에 넣는 최근 본문 수(실제 모델 어댑터 입력용). */
    static final int RECENT_CONTENT_LIMIT = 20;
    /** product_review_summary.summary_text 길이(V40). 어댑터가 더 길게 돌려줘도 저장이 실패하지 않게 자른다. */
    static final int SUMMARY_TEXT_MAX_LENGTH = 500;

    private final ReviewRepository reviewRepository;
    private final ProductReviewSummaryRepository productReviewSummaryRepository;
    private final ReviewQueryService reviewQueryService;
    private final LlmPort llmPort;

    public ReviewSummaryService(ReviewRepository reviewRepository, ProductReviewSummaryRepository productReviewSummaryRepository,
            ReviewQueryService reviewQueryService, LlmPort llmPort) {
        this.reviewRepository = reviewRepository;
        this.productReviewSummaryRepository = productReviewSummaryRepository;
        this.reviewQueryService = reviewQueryService;
        this.llmPort = llmPort;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void recalculate(Long productId) {
        List<RatingCountProjection> counts = reviewRepository.countByRating(productId, ReviewStatus.VISIBLE);
        long reviewCount = counts.stream().mapToLong(RatingCountProjection::getReviewCount).sum();
        if (reviewCount == 0) {
            productReviewSummaryRepository.deleteByProductId(productId);
            log.info("[ReviewSummary] 공개 리뷰 없음 → 요약 삭제 productId={}", productId);
            return;
        }
        long ratingSum = counts.stream().mapToLong(count -> count.getRating() * count.getReviewCount()).sum();
        List<String> topKeywordLabels = reviewQueryService.keywordCounts(productId).stream()
                .limit(TOP_KEYWORD_LIMIT)
                .map(ReviewSummaryResponse.KeywordCount::label)
                .toList();
        List<String> recentContents = reviewRepository.findRecentContents(productId, ReviewStatus.VISIBLE,
                PageRequest.of(0, RECENT_CONTENT_LIMIT));
        ReviewSummaryPrompt prompt = new ReviewSummaryPrompt(ReviewQueryService.roundAverage((double) ratingSum / reviewCount),
                reviewCount, topKeywordLabels, recentContents);
        String summary = llmPort.summarizeReviews(prompt);
        String stored = summary.length() > SUMMARY_TEXT_MAX_LENGTH ? summary.substring(0, SUMMARY_TEXT_MAX_LENGTH) : summary;
        productReviewSummaryRepository.upsert(productId, stored, (int) reviewCount, LocalDateTime.now());
        log.info("[ReviewSummary] 재계산 productId={} reviewCount={}", productId, reviewCount);
    }
}
