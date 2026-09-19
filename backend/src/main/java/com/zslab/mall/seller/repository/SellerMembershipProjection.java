package com.zslab.mall.seller.repository;

import com.zslab.mall.seller.enums.SellerStatus;

/** user가 소속된 셀러의 id·상태 projection(Track 90-A 셀러 상태 가드·SellerActorResolver 해소용). */
public interface SellerMembershipProjection {

    Long getSellerId();

    SellerStatus getStatus();
}
