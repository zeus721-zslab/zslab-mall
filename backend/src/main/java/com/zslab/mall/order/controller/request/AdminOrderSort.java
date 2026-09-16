package com.zslab.mall.order.controller.request;

/** 관리자 주문 목록 정렬(Track 79 D-168). 허용 외 값은 Spring 변환 실패 400(AdminProductSort 선례). */
public enum AdminOrderSort {
    LATEST,
    OLDEST
}
