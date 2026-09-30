package com.zslab.mall.faq.repository;

import com.zslab.mall.faq.entity.Faq;
import com.zslab.mall.faq.enums.FaqCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 삭제 행 제외는 {@code @SQLRestriction}이 자동 적용한다. 카테고리 사이 순서(enum 선언 순서)는 DB 문자열 정렬과 달라 서비스가 정렬한다. */
public interface FaqRepository extends JpaRepository<Faq, Long> {

    List<Faq> findByVisibleTrue();

    List<Faq> findByCategory(FaqCategory category);

    /** 카테고리 끝 다음 sortOrder(비어 있으면 0). 동시 등록이 같은 값을 받으면 id 순으로 갈린다(순서 표시만 영향). */
    @Query("SELECT COALESCE(MAX(f.sortOrder) + 1, 0) FROM Faq f WHERE f.category = :category")
    int nextSortOrder(@Param("category") FaqCategory category);
}
