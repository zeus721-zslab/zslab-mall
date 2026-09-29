package com.zslab.mall.review.service;

import com.zslab.mall.review.controller.response.ReviewHelpfulResponse;
import com.zslab.mall.review.entity.Review;
import com.zslab.mall.review.enums.ReviewStatus;
import com.zslab.mall.review.exception.ReviewNotEligibleException;
import com.zslab.mall.review.exception.ReviewNotFoundException;
import com.zslab.mall.review.repository.ReviewRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리뷰 "도움됐어요" 토글(Track 106-1). 로그인 구매자만, 본인 리뷰 제외, 공개 리뷰만. 1인 1회는 리뷰 행 락 아래 존재 확인 → 일반 INSERT로
 * 지키고 review_helpful PK가 최종 보장한다. helpful_count는 행이 실제로 추가·삭제됐을 때만 원자적 UPDATE로 증감한다(같은 요청 반복 = 멱등).
 *
 * <p><b>락 순서</b>: 리뷰 행을 먼저 {@code FOR UPDATE}로 잡는다. review_helpful INSERT는 FK 검사로 리뷰 행 공유 락을 잡으므로, 락 없이
 * INSERT → helpful_count UPDATE(배타 락) 순서로 가면 서로 다른 사용자의 동시 요청이 공유 → 배타 업그레이드에서 교착한다. 같은 리뷰의 토글만
 * 직렬화되고 다른 리뷰와는 경합하지 않는다.
 */
@Service
@Transactional
public class ReviewHelpfulService {

    private final ReviewRepository reviewRepository;

    public ReviewHelpfulService(ReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    /**
     * 도움됐어요 추가(이미 눌렀으면 변화 없음).
     *
     * @throws ReviewNotFoundException 미존재·삭제·숨김 리뷰(404)
     * @throws ReviewNotEligibleException 본인 리뷰(422)
     */
    public ReviewHelpfulResponse add(Long buyerId, String reviewPublicId) {
        Review review = requireHelpableReview(buyerId, reviewPublicId);
        // 리뷰 행 락 아래라 확인과 INSERT 사이에 같은 리뷰의 다른 토글이 끼어들지 않는다.
        if (reviewRepository.countHelpful(review.getId(), buyerId) == 0) {
            reviewRepository.insertHelpful(review.getId(), buyerId, LocalDateTime.now());
            reviewRepository.addHelpfulCount(review.getId(), 1);
        }
        return new ReviewHelpfulResponse(true, reviewRepository.findHelpfulCount(review.getId()));
    }

    /**
     * 도움됐어요 취소(누른 적 없으면 변화 없음).
     *
     * @throws ReviewNotFoundException 미존재·삭제·숨김 리뷰(404)
     * @throws ReviewNotEligibleException 본인 리뷰(422)
     */
    public ReviewHelpfulResponse remove(Long buyerId, String reviewPublicId) {
        Review review = requireHelpableReview(buyerId, reviewPublicId);
        if (reviewRepository.deleteHelpful(review.getId(), buyerId) == 1) {
            reviewRepository.addHelpfulCount(review.getId(), -1);
        }
        return new ReviewHelpfulResponse(false, reviewRepository.findHelpfulCount(review.getId()));
    }

    private Review requireHelpableReview(Long buyerId, String reviewPublicId) {
        Review review = reviewRepository.findByPublicIdForUpdate(reviewPublicId)
                .filter(found -> found.getStatus() == ReviewStatus.VISIBLE)
                .orElseThrow(() -> new ReviewNotFoundException("리뷰를 찾을 수 없습니다: " + reviewPublicId));
        if (review.isWrittenBy(buyerId)) {
            throw new ReviewNotEligibleException("본인 리뷰에는 도움됐어요를 누를 수 없습니다.");
        }
        return review;
    }
}
