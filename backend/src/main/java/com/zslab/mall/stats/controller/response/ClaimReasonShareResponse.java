package com.zslab.mall.stats.controller.response;

/**
 * 클레임 사유 분포 1행(기간 내 요청 기준·건수 내림차순). reason_code는 DB 무제약 VARCHAR라 {@code ClaimReasonCode} enum 외 값도
 * 문자열 그대로 내린다(FE가 라벨 매핑·미매핑은 원문 표기). share = 전체 클레임 대비 %(소수 2자리).
 */
public record ClaimReasonShareResponse(
        String reasonCode,
        long count,
        double share) {
}
