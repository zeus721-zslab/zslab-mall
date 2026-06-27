package com.zslab.mall.seller.repository;

import com.zslab.mall.seller.entity.SellerSalesDaily;
import com.zslab.mall.seller.entity.SellerSalesDailyId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerSalesDailyRepository extends JpaRepository<SellerSalesDaily, SellerSalesDailyId> {
}
