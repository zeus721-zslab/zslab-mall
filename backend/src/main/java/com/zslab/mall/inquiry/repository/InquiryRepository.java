package com.zslab.mall.inquiry.repository;

import com.zslab.mall.inquiry.entity.Inquiry;
import com.zslab.mall.inquiry.enums.InquiryCategory;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 운영자 문의 저장소(Track 106-4). JPQL은 엔티티의 {@code @SQLRestriction("deleted_at IS NULL")}이 자동 적용돼 삭제 문의를 제외한다.
 * 모든 JPQL 변수는 :name 바인딩이다(SQL injection 위험 없음).
 */
public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    /** 구매자 수정·삭제·답변 확인·관리자 답변의 직렬화(같은 문의 동시 명령 → 둘째는 락 해제 후 최신 상태로 판정). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inquiry i WHERE i.publicId = :publicId")
    Optional<Inquiry> findByPublicIdForUpdate(@Param("publicId") String publicId);

    /** 잠금 없는 단건 조회(삭제 제외 · D-253 답안 초안). */
    Optional<Inquiry> findByPublicId(String publicId);

    /** 내 문의(삭제 제외). 정렬은 호출부 Pageable Sort. ix_inquiry_buyer_list 탐색. */
    Page<Inquiry> findByBuyerId(Long buyerId, Pageable pageable);

    /**
     * 관리자 목록. answered가 null이면 전체, true면 답변 완료, false면 미답변. category가 null이면 전체. 정렬은 호출부 Pageable Sort.
     */
    @Query(value = "SELECT i FROM Inquiry i "
            + "WHERE (:answered IS NULL OR (:answered = true AND i.answeredAt IS NOT NULL) OR (:answered = false AND i.answeredAt IS NULL)) "
            + "AND (:category IS NULL OR i.category = :category)",
            countQuery = "SELECT COUNT(i) FROM Inquiry i "
                    + "WHERE (:answered IS NULL OR (:answered = true AND i.answeredAt IS NOT NULL) "
                    + "OR (:answered = false AND i.answeredAt IS NULL)) "
                    + "AND (:category IS NULL OR i.category = :category)")
    Page<Inquiry> findForAdmin(@Param("answered") Boolean answered, @Param("category") InquiryCategory category, Pageable pageable);

    /** 대시보드 미답변 문의 수(삭제 제외). ix_inquiry_unanswered 탐색. */
    long countByAnsweredAtIsNull();
}
