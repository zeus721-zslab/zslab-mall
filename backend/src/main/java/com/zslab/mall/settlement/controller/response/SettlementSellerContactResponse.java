package com.zslab.mall.settlement.controller.response;

/** 정산 상세 셀러 연락처(마스킹·Track 85). 없으면 null. */
public record SettlementSellerContactResponse(String contactEmail, String contactPhone) {
}
