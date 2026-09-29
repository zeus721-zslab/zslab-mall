package com.zslab.mall.review.repository;

import com.zslab.mall.review.entity.ProductReviewSummary;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductReviewSummaryRepository extends JpaRepository<ProductReviewSummary, Long> {

    /**
     * 요약 저장(있으면 덮어쓰기). 재계산은 매번 원천 전체를 다시 읽어 만든 값이라 마지막 계산이 곧 최신이다(전용 단일 스레드 실행기로 직렬화).
     * 모든 변수는 :name 바인딩 사용, SQL injection 위험 없음.
     */
    @Modifying
    @Query(value = "INSERT INTO product_review_summary (product_id, summary_text, review_count, updated_at) "
            + "VALUES (:productId, :summaryText, :reviewCount, :now) "
            + "ON DUPLICATE KEY UPDATE summary_text = VALUES(summary_text), review_count = VALUES(review_count), "
            + "updated_at = VALUES(updated_at)", nativeQuery = true)
    int upsert(@Param("productId") Long productId, @Param("summaryText") String summaryText,
            @Param("reviewCount") int reviewCount, @Param("now") LocalDateTime now);

    /** 공개 리뷰가 0건이 되면 요약을 지운다("요약 없음"). :productId 바인딩. */
    @Modifying
    @Query("DELETE FROM ProductReviewSummary s WHERE s.productId = :productId")
    int deleteByProductId(@Param("productId") Long productId);
}
