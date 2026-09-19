package com.zslab.mall.seller.controller.response;

import com.zslab.mall.seller.enums.SellerStatus;
import java.time.LocalDateTime;

/**
 * 관리자 셀러 목록 행(Track 89-D). productCount는 활성(미삭제) 상품 수·hasPrimaryBankAccount는 주 정산계좌 보유 여부이며
 * 둘 다 페이지 단위 배치 집계다.
 */
public record AdminSellerSummaryResponse(
        String sellerPublicId,
        String companyName,
        String businessNo,
        String ceoName,
        String contactEmail,
        String contactPhone,
        SellerStatus status,
        long productCount,
        boolean hasPrimaryBankAccount,
        LocalDateTime createdAt) {
}
