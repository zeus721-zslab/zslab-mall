package com.zslab.mall.delivery.controller.request;

/** 관리자 배송 목록 정렬(Track 89-B·발송일 shipped_at 기준·AdminClaimSort 선례). 허용 외 값은 Spring 변환 실패 400. */
public enum AdminDeliverySort {
    LATEST,
    OLDEST
}
