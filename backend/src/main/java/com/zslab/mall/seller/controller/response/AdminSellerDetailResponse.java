package com.zslab.mall.seller.controller.response;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.seller.enums.SellerBankAccountStatus;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.service.SellerTerminationBlock;
import com.zslab.mall.settlement.enums.SettlementStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 관리자 셀러 상세(Track 89-D). 기본 정보·소속 구성원·주 계좌(끝 4자리)·계좌 목록(Track 89-F·등록순·주 계좌 전환·수정 대상 선택용)·
 * 집계(상품 상태별 수·주문 수·구매확정 매출·정산 상태별)·종료 가능 여부({@code terminable}·{@code terminationBlocks} = 실제 전이와 같은
 * 가드 판정)·경고(주 계좌 없음·SALE 상품 존재)를 담는다. 연락처는 마스킹하지 않는다(회원 상세 선례·사업 연락처).
 */
public record AdminSellerDetailResponse(
        String sellerPublicId,
        String companyName,
        String businessNo,
        String ceoName,
        String contactEmail,
        String contactPhone,
        SellerStatus status,
        Integer commissionRate,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<Member> members,
        BankAccount primaryBankAccount,
        List<AdminSellerBankAccountResponse> bankAccounts,
        long productCount,
        Map<ProductStatus, Long> productCountByStatus,
        long orderCount,
        long confirmedSalesAmount,
        List<SettlementTotal> settlements,
        boolean terminable,
        List<SellerTerminationBlock> terminationBlocks,
        Warnings warnings) {

    /**
     * 소속 구성원. seller_user 행은 탈퇴·삭제 후에도 유지되므로(D-23 B-d6) 탈퇴 회원은 {@code withdrawnAt}으로, soft-delete 회원은
     * user 필드 전부 null({@code userPublicId} 포함)로 표기한다.
     */
    public record Member(String userPublicId, String email, String name, RoleCode roleCode, LocalDateTime withdrawnAt) {
    }

    /** 현재 주 정산계좌(끝 4자리·{@code SettlementBankAccountResponse} 마스킹 규칙). 없으면 null. */
    public record BankAccount(
            Long id,
            String bankCode,
            String accountHolder,
            String accountNumberSuffix,
            SellerBankAccountStatus status,
            LocalDateTime verifiedAt) {
    }

    /** 정산 상태별 건수·정산금액 합(전 기간). 건수 0인 상태는 없다. */
    public record SettlementTotal(SettlementStatus status, long count, long netAmount) {
    }

    /** 가드가 아닌 경고 정보. 화면이 종료·정지 확인 문구에 반영한다. */
    public record Warnings(boolean primaryBankAccountMissing, long saleProductCount) {
    }
}
