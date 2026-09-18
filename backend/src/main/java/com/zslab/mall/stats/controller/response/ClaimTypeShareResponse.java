package com.zslab.mall.stats.controller.response;

import com.zslab.mall.claim.enums.ClaimType;

/** 클레임 유형 분포 1행(기간 내 요청 기준·건수 내림차순). share = 전체 클레임 대비 %(소수 2자리·전체 0이면 0). */
public record ClaimTypeShareResponse(
        ClaimType type,
        long count,
        double share) {
}
