package com.zslab.mall.review.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.review.entity.Review;
import com.zslab.mall.review.enums.ReviewStatus;
import com.zslab.mall.review.event.ReviewChangedEvent;
import com.zslab.mall.review.exception.ReviewInvalidStateException;
import com.zslab.mall.review.exception.ReviewNotFoundException;
import com.zslab.mall.review.repository.ReviewRepository;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 리뷰 숨김·숨김 해제(Track 106-1). 비관적 락으로 같은 리뷰의 동시 명령을 직렬화하고 같은 트랜잭션에서 감사(UPDATE·REVIEW·
 * before {status} / after {status, reason})를 적재한다(셀러 상태 전이 선례). 리뷰 본문은 감사 diff에 싣지 않는다 — 숨김은 본문을 바꾸지 않고,
 * append-only 감사 로그에 사용자 작성 글을 복제하지 않는다.
 *
 * <p>감사 호출은 이 클래스에만 둔다(AuditFieldMaskingPolicyTest가 감사 호출 파일의 Map 키를 파일 단위로 스캔한다).
 */
@Slf4j
@Service
@Transactional
public class AdminReviewCommandService {

    private final ReviewRepository reviewRepository;
    private final AuditRecorder auditRecorder;
    private final TracedEventPublisher eventPublisher;

    public AdminReviewCommandService(ReviewRepository reviewRepository, AuditRecorder auditRecorder,
            TracedEventPublisher eventPublisher) {
        this.reviewRepository = reviewRepository;
        this.auditRecorder = auditRecorder;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @param target 목표 상태(VISIBLE·HIDDEN·DTO @Pattern 선검증)
     * @param reason 사유(필수·감사 이력)
     * @throws ReviewNotFoundException 미존재·삭제 리뷰(404)
     * @throws ReviewInvalidStateException 같은 상태 재요청(422)
     */
    public void changeStatus(String reviewPublicId, ReviewStatus target, String reason, AuditContext auditContext) {
        Review review = reviewRepository.findByPublicIdForUpdate(reviewPublicId)
                .orElseThrow(() -> new ReviewNotFoundException("리뷰를 찾을 수 없습니다: " + reviewPublicId));
        ReviewStatus before = review.getStatus();
        String trimmedReason = reason.trim();
        try {
            review.changeStatus(target, trimmedReason);
        } catch (IllegalStateException exception) {
            throw new ReviewInvalidStateException(exception.getMessage() + " reviewPublicId=" + reviewPublicId);
        }
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.REVIEW, review.getId(),
                Map.of("status", before.name()),
                Map.of("status", target.name(), "reason", trimmedReason));
        eventPublisher.publishEvent(new ReviewChangedEvent(review.getProductId()));
        log.info("[AdminReview] 상태 전이 {} → {} reviewPublicId={} byActor={}", before, target, reviewPublicId,
                auditContext.actorUserId());
    }
}
