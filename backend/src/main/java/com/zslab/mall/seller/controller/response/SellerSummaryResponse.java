package com.zslab.mall.seller.controller.response;

import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.enums.SellerStatus;

/** 관리자 셀러 선택 목록 항목(Track 76·최소 필드). 상품 등록 폼의 셀러 드롭다운이 소비한다. */
public record SellerSummaryResponse(String sellerPublicId, String companyName, SellerStatus status) {

    public static SellerSummaryResponse from(Seller seller) {
        return new SellerSummaryResponse(seller.getPublicId(), seller.getCompanyName(), seller.getStatus());
    }
}
