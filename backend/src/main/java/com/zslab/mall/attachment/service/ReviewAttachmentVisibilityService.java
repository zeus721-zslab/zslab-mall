package com.zslab.mall.attachment.service;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.attachment.repository.AttachmentRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.review.enums.ReviewStatus;
import com.zslab.mall.review.repository.ReviewRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리뷰 사진 공개 판정(Track 106-1·결정 1 A — 공개 상태 인가 서빙). 서빙 요청 키로 attachment 행을 찾아, 연결된 리뷰가 공개(VISIBLE)이고
 * 삭제되지 않았을 때만 공개로 본다. 미연결(작성 전·수정으로 빠진 사진)·숨김·삭제·다른 대상 유형·행 없음·행 2개 이상은 전부 비공개(호출부 404)로
 * 수렴시켜 존재 여부를 드러내지 않는다. 썸네일({@code _thumb}) 키는 원본 행의 판정을 따른다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class ReviewAttachmentVisibilityService {

    private final AttachmentRepository attachmentRepository;
    private final ReviewRepository reviewRepository;

    public ReviewAttachmentVisibilityService(AttachmentRepository attachmentRepository, ReviewRepository reviewRepository) {
        this.attachmentRepository = attachmentRepository;
        this.reviewRepository = reviewRepository;
    }

    /**
     * @param relativeKey 서빙 요청 키(예 {@code reviews/2026/09/{ULID}.jpg} 또는 {@code ..._thumb.jpg})
     * @return 공개 리뷰에 연결된 사진이면 true
     */
    public boolean isPublic(String relativeKey) {
        List<String> filePaths = ClaimAttachmentAuthorizationService.originalFilePathCandidates(relativeKey);
        if (filePaths.isEmpty()) {
            return false;
        }
        List<Attachment> found = attachmentRepository.findByFilePathIn(filePaths);
        if (found.size() != 1) {
            log.debug("[ReviewPhotoVisibility] 첨부 행 수 불일치 key={} found={}", relativeKey, found.size());
            return false;
        }
        Attachment attachment = found.get(0);
        if (attachment.getTargetType() != PolymorphicTargetType.REVIEW || !attachment.isLinked()) {
            log.debug("[ReviewPhotoVisibility] 리뷰 연결 사진 아님 key={} targetType={} linked={}", relativeKey,
                    attachment.getTargetType(), attachment.isLinked());
            return false;
        }
        // 삭제 리뷰는 @SQLRestriction으로 조회되지 않는다.
        return reviewRepository.findById(attachment.getTargetId())
                .map(review -> review.getStatus() == ReviewStatus.VISIBLE)
                .orElse(false);
    }
}
