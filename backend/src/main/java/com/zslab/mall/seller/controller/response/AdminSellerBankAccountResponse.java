package com.zslab.mall.seller.controller.response;

import com.zslab.mall.seller.entity.SellerBankAccount;
import com.zslab.mall.seller.enums.SellerBankAccountStatus;
import java.time.LocalDateTime;

/**
 * 관리자 셀러 정산계좌 행(Track 89-F·D-188). 계좌번호는 끝 4자리({@code accountNumberSuffix})만 노출한다 — 전체 계좌번호는 어떤 응답에도
 * 싣지 않는다({@code SettlementBankAccountResponse}·{@code AdminSellerDetailResponse.BankAccount} 마스킹 규칙 정합).
 *
 * <p>{@code referencedBySettlement} = 정산이 이 행을 지급 계좌로 참조하는지(외부 검토 Q6·미리보기). 판정 기준은 수정 409
 * ({@code AdminSellerBankAccountCommandService.update}·{@code SettlementRepository.existsByBankAccountId})와 같다 — "settlement.bank_account_id가
 * 이 id를 가리키는 정산이 1건이라도 존재". 화면은 true면 수정 버튼을 비활성하고, 조회~요청 사이 변화는 서버 409가 그대로 막는다(89-D terminable 선례).
 */
public record AdminSellerBankAccountResponse(
        Long id,
        String bankCode,
        String accountHolder,
        String accountNumberSuffix,
        boolean isPrimary,
        boolean referencedBySettlement,
        SellerBankAccountStatus status,
        LocalDateTime verifiedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static AdminSellerBankAccountResponse of(SellerBankAccount account, boolean referencedBySettlement) {
        return new AdminSellerBankAccountResponse(account.getId(), account.getBankCode(), account.getAccountHolder(),
                account.accountNumberSuffix(), account.isPrimary(), referencedBySettlement, account.getStatus(),
                account.getVerifiedAt(), account.getCreatedAt(), account.getUpdatedAt());
    }
}
