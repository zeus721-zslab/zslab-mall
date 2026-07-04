package com.zslab.mall.product.repository;

import com.zslab.mall.product.entity.ProductOptionValue;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductOptionValueRepository extends JpaRepository<ProductOptionValue, Long> {

    List<ProductOptionValue> findByOptionGroupId(Long optionGroupId);

    /** 여러 옵션 그룹의 옵션값을 일괄 조회한다(Track 44 단건 카탈로그·옵션값 라벨 배치·N+1 회피). */
    List<ProductOptionValue> findByOptionGroupIdIn(Collection<Long> optionGroupIds);
}
