package com.zslab.mall.order.repository;

/**
 * seller별 정산 gross(총 매출) 집계 projection(Track 48 P2). 정산 월 배치가 seller 단위로 Settlement를 생성하므로
 * seller_id로 GROUP BY한 합계를 반환한다.
 */
public interface SellerGrossProjection {

    Long getSellerId();

    Long getGrossAmount();
}
