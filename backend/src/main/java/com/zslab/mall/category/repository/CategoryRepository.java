package com.zslab.mall.category.repository;

import com.zslab.mall.category.entity.Category;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** 여러 카테고리를 일괄 조회한다(Track 44 카탈로그·카테고리명 enrich·N+1 회피). */
    List<Category> findByIdIn(Collection<Long> ids);
}
