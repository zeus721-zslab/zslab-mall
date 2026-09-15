package com.zslab.mall.category.repository;

import com.zslab.mall.category.entity.Category;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** 여러 카테고리를 일괄 조회한다(Track 44 카탈로그·카테고리명 enrich·N+1 회피). */
    List<Category> findByIdIn(Collection<Long> ids);

    /**
     * 루트 카테고리(parent IS NULL) 전체를 sort_order·id 오름차순으로 조회한다(Track 72 공개 카테고리 목록). 루트 판별을 depth가
     * 아닌 parent로 하는 이유: 데모 시드(depth 0)와 API 생성분(depth 1)의 depth 값이 불일치하기 때문이다(D-161).
     * 삭제 행 제외는 {@code @SQLRestriction}이 자동 적용한다.
     */
    List<Category> findByParentIsNullOrderBySortOrderAscIdAsc();
}
