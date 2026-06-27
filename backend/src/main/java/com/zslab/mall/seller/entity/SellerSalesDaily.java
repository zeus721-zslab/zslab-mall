package com.zslab.mall.seller.entity;

import com.zslab.mall.common.entity.AbstractAggregateEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매자 일별 집계 Read Model(ARCHIVE·집계·FK 미적용).
 *
 * <p>복합 PK (seller_id, sale_date) — @IdClass(SellerSalesDailyId.class)(D-83 Q1 결정).
 * seller_id는 Seller.id 논리 참조·FK 미적용·@GeneratedValue 없음.
 * updated_at은 AbstractAggregateEntity 상속·배치 갱신 시각 추적.
 */
@Entity
@Table(name = "seller_sales_daily")
@IdClass(SellerSalesDailyId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerSalesDaily extends AbstractAggregateEntity {

    @Id
    @Column(name = "seller_id")
    @EqualsAndHashCode.Include
    private Long sellerId;

    @Id
    @Column(name = "sale_date")
    @EqualsAndHashCode.Include
    private LocalDate saleDate;

    @Column(name = "order_count", nullable = false)
    private int orderCount;

    @Column(name = "gross_amount", nullable = false)
    private Long grossAmount;

    @Column(name = "refund_amount", nullable = false)
    private Long refundAmount;

    @Column(name = "net_amount", nullable = false)
    private Long netAmount;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static SellerSalesDaily create(
            Long sellerId,
            LocalDate saleDate,
            int orderCount,
            long grossAmount,
            long refundAmount,
            long netAmount) {
        if (sellerId == null || saleDate == null) {
            throw new IllegalArgumentException("SellerSalesDaily 필수값 누락(sellerId·saleDate).");
        }
        SellerSalesDaily daily = new SellerSalesDaily();
        daily.sellerId = sellerId;
        daily.saleDate = saleDate;
        daily.orderCount = orderCount;
        daily.grossAmount = grossAmount;
        daily.refundAmount = refundAmount;
        daily.netAmount = netAmount;
        return daily;
    }
}
