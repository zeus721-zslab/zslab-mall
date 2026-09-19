package com.zslab.mall.seller.service;

import com.zslab.mall.seller.enums.SellerTerminationBlockCode;

/** 종료 차단 사유 1건(코드·건수). {@link SellerTerminationGuard} 판정 결과이며 상세 응답·409 응답에 그대로 실린다. */
public record SellerTerminationBlock(SellerTerminationBlockCode code, long count) {
}
