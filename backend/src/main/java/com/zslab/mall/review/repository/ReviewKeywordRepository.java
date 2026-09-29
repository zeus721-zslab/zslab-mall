package com.zslab.mall.review.repository;

import com.zslab.mall.review.entity.ReviewKeyword;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewKeywordRepository extends JpaRepository<ReviewKeyword, Long> {

    /**
     * 상품에 쓸 수 있는 키워드 중 요청 code에 해당하는 것(기본 세트 + 상품 최상위 카테고리 세트). 결과 수가 요청 code 수보다 적으면 쓸 수 없는
     * code가 섞인 것이다. 모든 변수는 :codes·:topCategoryId 바인딩이다.
     */
    @Query("SELECT k FROM ReviewKeyword k WHERE k.code IN :codes AND (k.topCategoryId IS NULL OR k.topCategoryId = :topCategoryId)")
    List<ReviewKeyword> findUsableByCodeIn(@Param("codes") Collection<String> codes, @Param("topCategoryId") Long topCategoryId);

    /**
     * 상품에 쓸 수 있는 키워드 전체(작성 폼 선택지). 허용 조건은 {@link #findUsableByCodeIn}과 같다 — 선택지와 작성 검증이 어긋나지 않게.
     * 표시 순서·id 순. 모든 변수는 :topCategoryId 바인딩이다.
     */
    @Query("SELECT k FROM ReviewKeyword k WHERE k.topCategoryId IS NULL OR k.topCategoryId = :topCategoryId "
            + "ORDER BY k.displayOrder ASC, k.id ASC")
    List<ReviewKeyword> findUsable(@Param("topCategoryId") Long topCategoryId);

    List<ReviewKeyword> findByIdIn(Collection<Long> ids);

    Optional<ReviewKeyword> findByCode(String code);
}
