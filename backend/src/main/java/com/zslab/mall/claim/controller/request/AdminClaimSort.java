package com.zslab.mall.claim.controller.request;

/** 관리자 클레임 목록 정렬(Track 80 D-169·AdminOrderSort 선례). 허용 외 값은 Spring 변환 실패 400. */
public enum AdminClaimSort {
    LATEST,
    OLDEST
}
