package com.zslab.mall.productquestion.repository;

import com.zslab.mall.productquestion.entity.ProductQuestion;
import com.zslab.mall.productquestion.enums.ProductQuestionStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 상품 질문 저장소(Track 106-2). JPQL은 엔티티의 {@code @SQLRestriction("deleted_at IS NULL")}이 자동 적용돼 삭제 질문을 제외한다.
 * 모든 JPQL 변수는 :name 바인딩이다(SQL injection 위험 없음).
 */
public interface ProductQuestionRepository extends JpaRepository<ProductQuestion, Long> {

    /** 질문자 수정·삭제·셀러 답변·관리자 숨김의 직렬화(같은 질문 동시 명령 → 둘째는 락 해제 후 최신 상태로 판정). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM ProductQuestion q WHERE q.publicId = :publicId")
    Optional<ProductQuestion> findByPublicIdForUpdate(@Param("publicId") String publicId);

    /** 공개 목록(상품·상태 고정·미답변 포함). 정렬은 호출부 Pageable Sort. ix_product_question_product_list 탐색. */
    @Query("SELECT q FROM ProductQuestion q WHERE q.productId = :productId AND q.status = :status")
    Page<ProductQuestion> findPublicPage(
            @Param("productId") Long productId, @Param("status") ProductQuestionStatus status, Pageable pageable);

    /** 즉시 답 후보: 답변이 달린 공개 질문(최신순). 개수는 Pageable로 제한한다. */
    @Query("SELECT q FROM ProductQuestion q WHERE q.productId = :productId AND q.status = :status AND q.answeredAt IS NOT NULL "
            + "ORDER BY q.createdAt DESC, q.id DESC")
    List<ProductQuestion> findRecentAnswered(
            @Param("productId") Long productId, @Param("status") ProductQuestionStatus status, Pageable pageable);

    /** 내 질문(숨김 포함·삭제 제외). 정렬은 호출부 Pageable Sort. ix_product_question_buyer_list 탐색. */
    Page<ProductQuestion> findByBuyerId(Long buyerId, Pageable pageable);

    /**
     * 셀러 목록: 자기 상품(상품 조인)의 공개 질문. answered가 null이면 전체, true면 답변 완료, false면 미답변. 정렬은 호출부 Pageable Sort.
     * 상품 쪽 @SQLRestriction으로 삭제 상품의 질문은 빠진다.
     */
    @Query(value = "SELECT q FROM ProductQuestion q JOIN Product p ON p.id = q.productId "
            + "WHERE p.sellerId = :sellerId AND q.status = :status "
            + "AND (:answered IS NULL OR (:answered = true AND q.answeredAt IS NOT NULL) OR (:answered = false AND q.answeredAt IS NULL))",
            countQuery = "SELECT COUNT(q) FROM ProductQuestion q JOIN Product p ON p.id = q.productId "
                    + "WHERE p.sellerId = :sellerId AND q.status = :status "
                    + "AND (:answered IS NULL OR (:answered = true AND q.answeredAt IS NOT NULL) "
                    + "OR (:answered = false AND q.answeredAt IS NULL))")
    Page<ProductQuestion> findForSeller(@Param("sellerId") Long sellerId, @Param("status") ProductQuestionStatus status,
            @Param("answered") Boolean answered, Pageable pageable);

    /** 관리자 목록(상태 필터 선택·삭제 제외·최신순은 호출부 Pageable). */
    @Query("SELECT q FROM ProductQuestion q WHERE (:status IS NULL OR q.status = :status)")
    Page<ProductQuestion> findForAdmin(@Param("status") ProductQuestionStatus status, Pageable pageable);
}
