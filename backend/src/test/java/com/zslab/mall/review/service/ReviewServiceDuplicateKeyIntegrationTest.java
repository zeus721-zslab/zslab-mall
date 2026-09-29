package com.zslab.mall.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.zslab.mall.review.ReviewFixture;
import com.zslab.mall.review.entity.Review;
import com.zslab.mall.review.repository.ReviewRepository;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 동시 작성 판별이 실제 MariaDB 예외에서도 맞는지(Track 106-1 외부 검토 R2-6 · 셀프 리뷰 반영). 동시 작성 경합 테스트(C9)는 타이밍에 따라
 * 사전 확인에서 409가 나 이 판별을 거치지 않을 수 있어, 같은 품목 리뷰를 먼저 넣고 저장을 직접 부딪쳐 실 예외로 확인한다.
 */
class ReviewServiceDuplicateKeyIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10780L;
    private static final long BAND_TO = 10789L;
    private static final long BUYER = 10781L;
    private static final long SELLER = 10782L;
    private static final long PRODUCT = 10783L;
    private static final long VARIANT = 10784L;
    private static final long CATEGORY = 10785L;
    private static final long ORDER = 10786L;
    private static final long ITEM = 10787L;
    private static final long EXISTING_REVIEW = 10788L;

    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private ReviewFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new ReviewFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(BUYER);
        fixture.seedCatalog(SELLER, PRODUCT, VARIANT, CATEGORY);
        fixture.seedItem(ORDER, ITEM, BUYER, PRODUCT, VARIANT, SELLER, "CONFIRMED", null);
        fixture.insertReview(EXISTING_REVIEW, ITEM, PRODUCT, BUYER, 5, "VISIBLE", null, 0, LocalDateTime.now());
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("같은 품목 리뷰가 있는 상태에서 저장 → 실제 예외의 제약 이름이 uk_review_order_item으로 판별됨")
    void realDuplicateOnOrderItem_isDetected() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        DataIntegrityViolationException violation = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> tx.executeWithoutResult(status -> reviewRepository.saveAndFlush(
                        Review.create(ITEM, PRODUCT, BUYER, 4, "중복 저장", null, List.of()))));

        assertThat(violation).as("같은 품목 두 번째 저장은 무결성 위반이어야 한다").isNotNull();
        assertThat(ReviewService.isOrderItemDuplicate(violation)).isTrue();
    }
}
