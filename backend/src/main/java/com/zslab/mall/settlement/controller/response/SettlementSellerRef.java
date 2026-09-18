package com.zslab.mall.settlement.controller.response;

/** 정산 응답의 셀러 식별(Track 85). 종료·비식별화 셀러는 companyName이 null일 수 있다. */
public record SettlementSellerRef(String publicId, String companyName) {
}
