package com.zslab.mall.product.repository;

import com.zslab.mall.product.entity.ProductOptionValue;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductOptionValueRepository extends JpaRepository<ProductOptionValue, Long> {

    List<ProductOptionValue> findByOptionGroupId(Long optionGroupId);
}
