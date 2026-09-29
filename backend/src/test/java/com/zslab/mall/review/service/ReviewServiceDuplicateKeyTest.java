package com.zslab.mall.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** 작성 저장 실패 중 동시 작성 충돌(409)로 바꿀 위반만 고르는 판별 로직(Track 106-1 외부 검토 R2-6). */
class ReviewServiceDuplicateKeyTest {

    @Test
    @DisplayName("uk_review_order_item 위반(단독·테이블 접두 형태) → 409 대상")
    void orderItemUniqueKey_isDuplicate() {
        assertThat(ReviewService.isOrderItemDuplicate(wrap("uk_review_order_item"))).isTrue();
        assertThat(ReviewService.isOrderItemDuplicate(wrap("review.uk_review_order_item"))).isTrue();
    }

    @Test
    @DisplayName("다른 제약(public_id UNIQUE·FK·CHECK)·제약 이름 없음·제약 위반 아닌 무결성 오류 → 409 아님(원래 예외 전파)")
    void otherViolations_areNotDuplicate() {
        assertThat(ReviewService.isOrderItemDuplicate(wrap("uk_review_public_id"))).isFalse();
        assertThat(ReviewService.isOrderItemDuplicate(wrap("fk_review_product"))).isFalse();
        assertThat(ReviewService.isOrderItemDuplicate(wrap("chk_review_rating"))).isFalse();
        assertThat(ReviewService.isOrderItemDuplicate(wrap(null))).isFalse();
        assertThat(ReviewService.isOrderItemDuplicate(new DataIntegrityViolationException("too long",
                new DataException("Data too long for column 'content'", new SQLException("too long"))))).isFalse();
        assertThat(ReviewService.isOrderItemDuplicate(new DataIntegrityViolationException("no cause"))).isFalse();
    }

    private static DataIntegrityViolationException wrap(String constraintName) {
        return new DataIntegrityViolationException("violation",
                new ConstraintViolationException("violation", new SQLException("violation"), constraintName));
    }
}
