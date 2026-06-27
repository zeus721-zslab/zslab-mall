package com.zslab.mall.seller.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * SellerSalesDaily 복합 PK 클래스 (@IdClass·D-83 정합).
 */
public class SellerSalesDailyId implements Serializable {

    private Long sellerId;
    private LocalDate saleDate;

    public SellerSalesDailyId() {
    }

    public SellerSalesDailyId(Long sellerId, LocalDate saleDate) {
        this.sellerId = sellerId;
        this.saleDate = saleDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SellerSalesDailyId)) {
            return false;
        }
        SellerSalesDailyId that = (SellerSalesDailyId) o;
        return Objects.equals(sellerId, that.sellerId) && Objects.equals(saleDate, that.saleDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sellerId, saleDate);
    }
}
