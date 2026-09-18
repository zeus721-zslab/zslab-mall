package com.zslab.mall.dashboard.repository;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import java.time.LocalDateTime;

/** 최근 클레임 1행(Track 86). orderNo는 claim→order_item→order 조인으로 가져온다. */
public interface DashboardRecentClaimProjection {

    String getClaimPublicId();

    ClaimType getType();

    ClaimStatus getStatus();

    String getOrderNo();

    LocalDateTime getRequestedAt();
}
