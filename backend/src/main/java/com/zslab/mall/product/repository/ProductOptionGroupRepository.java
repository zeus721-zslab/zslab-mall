package com.zslab.mall.product.repository;

import com.zslab.mall.product.entity.ProductOptionGroup;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductOptionGroupRepository extends JpaRepository<ProductOptionGroup, Long> {

    List<ProductOptionGroup> findByProductId(Long productId);
}
