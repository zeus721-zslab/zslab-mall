package com.zslab.mall.seller.repository;

import com.zslab.mall.seller.entity.WithdrawnSeller;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 종료 판매자 아카이브 Repository.
 */
public interface WithdrawnSellerRepository extends JpaRepository<WithdrawnSeller, Long> {
}
