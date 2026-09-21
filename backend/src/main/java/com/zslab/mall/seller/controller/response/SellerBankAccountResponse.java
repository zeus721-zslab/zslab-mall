package com.zslab.mall.seller.controller.response;

import com.zslab.mall.seller.entity.SellerBankAccount;
import com.zslab.mall.seller.enums.SellerBankAccountStatus;
import java.time.LocalDateTime;

/**
 * 셀러 본인 정산계좌 행(Track 90-D-3·D-199). 계좌번호는 끝 4자리({@code accountNumberSuffix})만 노출한다 — 본인 계좌라도 전체 번호를 싣지
 * 않는다(관리자·정산 상세 마스킹 규칙 정합·D-188). 관리자 응답의 {@code referencedBySettlement}·{@code verifiedAt}·{@code updatedAt}은
 * 셀러 화면이 쓰지 않으므로 싣지 않는다(셀러는 수정·전환 권한이 없어 참조 여부가 의미 없음).
 */
public record SellerBankAccountResponse(
        Long id,
        String bankCode,
        String accountNumberSuffix,
        String accountHolder,
        boolean isPrimary,
        SellerBankAccountStatus status,
        LocalDateTime createdAt) {

    public static SellerBankAccountResponse of(SellerBankAccount account) {
        return new SellerBankAccountResponse(account.getId(), account.getBankCode(), account.accountNumberSuffix(),
                account.getAccountHolder(), account.isPrimary(), account.getStatus(), account.getCreatedAt());
    }
}
