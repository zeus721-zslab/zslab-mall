package com.zslab.mall.stats.controller.response;

/**
 * 셀러 클레임 상품별 분해 1행(Track 90-E-2·기간 내 요청 기준·건수 내림차순). productKey는 상품 public_id(soft-delete로 미존재면 null),
 * productName은 order_item.product_name 스냅샷(주문 시점), share = 셀러 전체 클레임 대비 %(소수 2자리·전체 0이면 0).
 */
public record SellerClaimProductShareResponse(
        String productKey,
        String productName,
        long count,
        double share) {
}
