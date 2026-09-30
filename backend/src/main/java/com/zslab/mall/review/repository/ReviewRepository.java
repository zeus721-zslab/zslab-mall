package com.zslab.mall.review.repository;

import com.zslab.mall.review.entity.Review;
import com.zslab.mall.review.enums.ReviewStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 리뷰 저장소(Track 106-1). JPQL은 엔티티의 {@code @SQLRestriction("deleted_at IS NULL")}이 자동 적용돼 삭제 리뷰를 제외한다. 삭제 리뷰까지 봐야
 * 하는 판정(재작성 불가·주문 응답 리뷰 상태)만 native로 쓴다. 모든 JPQL·native 변수는 :name 바인딩이다(SQL injection 위험 없음).
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByPublicId(String publicId);

    /** 작성자 수정·삭제·관리자 숨김의 직렬화(같은 리뷰 동시 명령 → 둘째는 락 해제 후 최신 상태로 판정). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Review r WHERE r.publicId = :publicId")
    Optional<Review> findByPublicIdForUpdate(@Param("publicId") String publicId);

    /** 품목에 리뷰가 있었는지(삭제 포함 — 삭제 후 재작성 불가). native라 @SQLRestriction이 붙지 않는다. :orderItemId 바인딩. */
    @Query(value = "SELECT COUNT(*) FROM review WHERE order_item_id = :orderItemId", nativeQuery = true)
    long countIncludingDeletedByOrderItemId(@Param("orderItemId") Long orderItemId);

    /**
     * 주문 응답의 품목별 리뷰 상태(삭제 포함 1쿼리 배치). 삭제된 리뷰는 reviewPublicId가 NULL이다(재작성 불가 표시만·링크 없음).
     * reviewStatus는 숨김 배지용(같은 쿼리 · 추가 조회 없음). native라 @SQLRestriction이 붙지 않는다. :orderItemIds 바인딩.
     */
    @Query(value = "SELECT order_item_id AS orderItemId, CASE WHEN deleted_at IS NULL THEN public_id END AS reviewPublicId, "
            + "status AS reviewStatus FROM review WHERE order_item_id IN (:orderItemIds)", nativeQuery = true)
    List<ReviewByOrderItemProjection> findWrittenByOrderItemIdIn(@Param("orderItemIds") Collection<Long> orderItemIds);

    /**
     * 공개 목록(상품·상태 고정 + 선택 필터). 필터 인자가 null(사진만은 false)이면 그 조건을 건너뛴다. 정렬은 호출부 Pageable Sort.
     * ix_review_product_list(product_id, status, deleted_at, created_at) 탐색.
     */
    @Query("SELECT r FROM Review r WHERE r.productId = :productId AND r.status = :status "
            + "AND (:keywordId IS NULL OR :keywordId MEMBER OF r.keywordIds) "
            + "AND (:optionLabel IS NULL OR r.optionLabel = :optionLabel) "
            + "AND (:photoOnly = false OR EXISTS (SELECT a.id FROM Attachment a "
            + "WHERE a.targetType = com.zslab.mall.common.enums.PolymorphicTargetType.REVIEW AND a.targetId = r.id))")
    Page<Review> findPublicPage(
            @Param("productId") Long productId,
            @Param("status") ReviewStatus status,
            @Param("keywordId") Long keywordId,
            @Param("optionLabel") String optionLabel,
            @Param("photoOnly") boolean photoOnly,
            Pageable pageable);

    /** 별점 분포(공개 리뷰·별점별 개수). */
    @Query("SELECT r.rating AS rating, COUNT(r) AS reviewCount FROM Review r "
            + "WHERE r.productId = :productId AND r.status = :status GROUP BY r.rating")
    List<RatingCountProjection> countByRating(@Param("productId") Long productId, @Param("status") ReviewStatus status);

    /** 키워드 집계(공개 리뷰·키워드별 선택 수). */
    @Query("SELECT k AS keywordId, COUNT(r) AS reviewCount FROM Review r JOIN r.keywordIds k "
            + "WHERE r.productId = :productId AND r.status = :status GROUP BY k")
    List<KeywordCountProjection> countByKeyword(@Param("productId") Long productId, @Param("status") ReviewStatus status);

    /** 목록 항목의 선택 키워드(페이지 리뷰 전체 1쿼리). */
    @Query("SELECT r.id AS reviewId, k AS keywordId FROM Review r JOIN r.keywordIds k WHERE r.id IN :reviewIds")
    List<ReviewKeywordIdProjection> findKeywordIdsByReviewIdIn(@Param("reviewIds") Collection<Long> reviewIds);

    /** 최근 사진(공개 리뷰의 첨부·최신 리뷰 순 → 리뷰 내 순서). 개수는 Pageable로 제한한다. */
    @Query("SELECT a.filePath AS filePath, r.publicId AS reviewPublicId FROM Attachment a, Review r "
            + "WHERE a.targetType = com.zslab.mall.common.enums.PolymorphicTargetType.REVIEW AND a.targetId = r.id "
            + "AND r.productId = :productId AND r.status = :status ORDER BY r.id DESC, a.displayOrder ASC")
    List<ReviewPhotoProjection> findRecentPhotos(
            @Param("productId") Long productId, @Param("status") ReviewStatus status, Pageable pageable);

    /** 최근 본문(요약 입력용·최신순). 개수는 Pageable로 제한한다. */
    @Query("SELECT r.content FROM Review r WHERE r.productId = :productId AND r.status = :status ORDER BY r.id DESC")
    List<String> findRecentContents(@Param("productId") Long productId, @Param("status") ReviewStatus status, Pageable pageable);

    /** 상품 질문 즉시 답 후보(Track 106-2·공개 리뷰·최신순). 개수는 Pageable로 제한한다. */
    @Query("SELECT r FROM Review r WHERE r.productId = :productId AND r.status = :status ORDER BY r.createdAt DESC, r.id DESC")
    List<Review> findRecentForSuggest(@Param("productId") Long productId, @Param("status") ReviewStatus status, Pageable pageable);

    /** 상품 목록 카드 별점(상품별 공개 리뷰 수·평균·1쿼리 배치). */
    @Query("SELECT r.productId AS productId, COUNT(r) AS reviewCount, AVG(r.rating) AS averageRating FROM Review r "
            + "WHERE r.productId IN :productIds AND r.status = :status GROUP BY r.productId")
    List<ProductRatingProjection> aggregateRatingByProductIdIn(
            @Param("productIds") Collection<Long> productIds, @Param("status") ReviewStatus status);

    /** 관리자 목록(상태 필터 선택·삭제 제외·최신순은 호출부 Pageable). */
    @Query("SELECT r FROM Review r WHERE (:status IS NULL OR r.status = :status)")
    Page<Review> findForAdmin(@Param("status") ReviewStatus status, Pageable pageable);

    // ---------- 도움됐어요(review_helpful PK로 1인 1회·helpful_count 원자적 증감) ----------

    /** 도움됐어요 행 수(0 또는 1). 호출부가 리뷰 행 락을 잡은 뒤 부른다(같은 리뷰 토글 직렬화). :reviewId·:userId 바인딩. */
    @Query(value = "SELECT COUNT(*) FROM review_helpful WHERE review_id = :reviewId AND user_id = :userId", nativeQuery = true)
    long countHelpful(@Param("reviewId") Long reviewId, @Param("userId") Long userId);

    /**
     * 도움됐어요 추가(일반 INSERT). 호출부가 리뷰 행 락 아래에서 {@link #countHelpful}로 없음을 확인한 뒤 부른다 — 오류를 경고로 바꾸는
     * INSERT IGNORE를 쓰지 않아 중복·FK 위반은 예외로 드러난다. :reviewId·:userId·:now 바인딩.
     */
    @Modifying
    @Query(value = "INSERT INTO review_helpful (review_id, user_id, created_at) VALUES (:reviewId, :userId, :now)", nativeQuery = true)
    int insertHelpful(@Param("reviewId") Long reviewId, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 도움됐어요 취소. 없으면 영향 행 0(멱등). :reviewId·:userId 바인딩. */
    @Modifying
    @Query(value = "DELETE FROM review_helpful WHERE review_id = :reviewId AND user_id = :userId", nativeQuery = true)
    int deleteHelpful(@Param("reviewId") Long reviewId, @Param("userId") Long userId);

    /** helpful_count 원자적 증감(읽고-쓰기 경쟁 없이 DB에서 계산). 감소는 0 아래로 내려가지 않는다(CHECK와 이중). :id·:delta 바인딩. */
    @Modifying
    @Query(value = "UPDATE review SET helpful_count = helpful_count + :delta WHERE id = :id AND helpful_count + :delta >= 0",
            nativeQuery = true)
    int addHelpfulCount(@Param("id") Long id, @Param("delta") int delta);

    /** 목록에서 요청자가 누른 리뷰 id(페이지 리뷰 전체 1쿼리). :userId·:reviewIds 바인딩. */
    @Query(value = "SELECT review_id FROM review_helpful WHERE user_id = :userId AND review_id IN (:reviewIds)", nativeQuery = true)
    List<Long> findHelpedReviewIds(@Param("userId") Long userId, @Param("reviewIds") Collection<Long> reviewIds);

    /** 현재 helpful_count(원자적 UPDATE 뒤 응답용 재조회·영속성 컨텍스트 캐시를 거치지 않는다). :id 바인딩. */
    @Query(value = "SELECT helpful_count FROM review WHERE id = :id", nativeQuery = true)
    int findHelpfulCount(@Param("id") Long id);
}
