package com.zslab.mall.review.enums;

/**
 * 리뷰 공개 상태(Track 106-1·V40 chk_review_status). 작성 시 VISIBLE이며 관리자만 숨김·숨김 해제한다. 작성자 삭제는 상태가 아니라
 * soft delete(deleted_at)로 표현한다(삭제 후 재작성 불가).
 */
public enum ReviewStatus {

    VISIBLE,
    HIDDEN;

    /** 관리자 전이 합법성: 같은 상태로의 재요청은 불법(셀러 상태 전이 선례·422). */
    public boolean canTransitionTo(ReviewStatus target) {
        return target != null && target != this;
    }
}
