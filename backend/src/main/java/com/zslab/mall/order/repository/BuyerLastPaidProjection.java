package com.zslab.mall.order.repository;

import java.time.LocalDateTime;

/** 구매자별 결제 완료 최근 시각 projection(Track 84 관리자 회원 목록·buyer_id GROUP BY). */
public interface BuyerLastPaidProjection {
    Long getBuyerId();
    LocalDateTime getLastPaidAt();
}
