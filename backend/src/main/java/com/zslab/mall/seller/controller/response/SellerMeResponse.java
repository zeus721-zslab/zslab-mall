package com.zslab.mall.seller.controller.response;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.seller.enums.SellerStatus;

/**
 * 셀러 본인 정보(Track 90-B-1). 셀러 패널 대시보드 상단·정지 안내 배너가 진입 시점에 소비한다.
 *
 * @param sellerPublicId         소속 셀러 public_id(slr_)
 * @param companyName            상호
 * @param status                 셀러 상태(ACTIVE·SUSPENDED — PENDING·TERMINATED는 resolver가 401로 차단하므로 도달하지 않음)
 * @param roleCode               이 사용자의 셀러 내 역할(SELLER_OWNER·SELLER_MANAGER·SELLER_STAFF)
 * @param pendingSettlementCount 관리자 정상처리 전(PENDING) 정산 건수. 금액은 싣지 않는다(셀러 정산 API는 CONFIRMED·PAID만 노출·D-179 결정 11)
 * @param bankAccountRegistered  주 정산계좌 등록 여부(계좌 정보는 싣지 않는다)
 */
public record SellerMeResponse(
        String sellerPublicId,
        String companyName,
        SellerStatus status,
        RoleCode roleCode,
        long pendingSettlementCount,
        boolean bankAccountRegistered) {
}
