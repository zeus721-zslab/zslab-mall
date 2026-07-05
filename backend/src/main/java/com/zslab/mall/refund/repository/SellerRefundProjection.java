package com.zslab.mall.refund.repository;

/**
 * seller별 정산 refund(환불액) 집계 projection(Track 48 P2). refund→claim→order_item 경로로 seller에 귀속한 뒤
 * seller_id로 GROUP BY한 합계를 반환한다.
 */
public interface SellerRefundProjection {

    Long getSellerId();

    Long getRefundAmount();
}
