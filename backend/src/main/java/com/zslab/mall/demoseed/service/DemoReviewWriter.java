package com.zslab.mall.demoseed.service;

import com.zslab.mall.demoseed.repository.DemoSeedRepository;
import com.zslab.mall.demoseed.service.DemoReviewPlanner.PlannedReview;
import com.zslab.mall.review.entity.Review;
import com.zslab.mall.review.service.ReviewService;
import java.util.HashSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리뷰 한 건(품목 하나)의 데모 적재(D-245). 이 메서드 하나가 품목 단위 트랜잭션이다 — 실패하면 그 리뷰와 시각 보정이 함께 롤백되고, 앞서 끝난
 * 품목은 각자 커밋돼 남는다. 쓰기는 ReviewService.create(사진 없음)를 호출하며, 커밋 후 요약 재계산 이벤트도 기존 경로 그대로 발행된다. 서비스
 * 직접 호출로 빠지는 DTO 검증(본문 1~1000자 · 키워드 10개 이하·중복 없음)은 여기서 다시 확인한다.
 */
@Slf4j
@Service
public class DemoReviewWriter {

    static final int CONTENT_MAX_LENGTH = 1000;
    static final int KEYWORD_MAX_COUNT = 10;

    private final ReviewService reviewService;
    private final DemoSeedRepository demoSeedRepository;

    public DemoReviewWriter(ReviewService reviewService, DemoSeedRepository demoSeedRepository) {
        this.reviewService = reviewService;
        this.demoSeedRepository = demoSeedRepository;
    }

    /** @throws IllegalStateException 본문·키워드가 서버 형식 제약을 벗어날 때(품목 롤백) */
    @Transactional
    public void write(PlannedReview planned) {
        requireFormat(planned);
        Review review = reviewService.create(planned.candidate().buyerId(), planned.candidate().orderItemPublicId(), planned.rating(),
                planned.keywordCodes(), planned.content(), List.of());
        demoSeedRepository.shiftReviewTime(review.getId(), planned.writtenAt());
        log.info("[DemoSeed] 리뷰 적재 orderItemPublicId={} rating={}", planned.candidate().orderItemPublicId(), planned.rating());
    }

    private static void requireFormat(PlannedReview planned) {
        int contentLength = planned.content().trim().length();
        if (contentLength == 0 || contentLength > CONTENT_MAX_LENGTH) {
            throw new IllegalStateException("리뷰 본문 길이가 서버 제약을 벗어났습니다: " + contentLength);
        }
        List<String> keywords = planned.keywordCodes();
        if (keywords.size() > KEYWORD_MAX_COUNT || new HashSet<>(keywords).size() != keywords.size()) {
            throw new IllegalStateException("리뷰 키워드가 서버 제약(최대 10개·중복 없음)을 벗어났습니다: " + keywords);
        }
    }
}
