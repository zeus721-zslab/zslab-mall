package com.zslab.mall.seller.entity;

import com.zslab.mall.common.entity.AbstractFullAuditableEntity;
import com.zslab.mall.seller.converter.AccountNumberEncryptionConverter;
import com.zslab.mall.seller.enums.SellerBankAccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매자 정산계좌(Seller 종속·ARCHIVE·full audit).
 *
 * <p>seller는 Seller Aggregate 내부 Root — D-01에 따라 @ManyToOne LAZY 허용.
 * accountNumber는 {@link AccountNumberEncryptionConverter}로 투명 암복호화(Track 89-F·D-188·SLR-2) — 필드는 평문, 컬럼은 {@code v1:} 암호문.
 * deleted_at 없음(ARCHIVE 분류) — soft-delete 미적용. 정산이 참조하는 행(settlement.bank_account_id)은 지급 이력 스냅샷이므로
 * 수정하지 않는다(서비스가 409로 차단·D-188).
 */
@Entity
@Table(name = "seller_bank_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerBankAccount extends AbstractFullAuditableEntity {

    private static final int SUFFIX_LENGTH = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false, updatable = false)
    private Seller seller;

    @Column(name = "bank_code", nullable = false, length = 20)
    private String bankCode;

    /** 평문 필드·DB는 AES-256-GCM 암호문(D-188). 응답에는 {@link #accountNumberSuffix()}만 노출한다. */
    @Convert(converter = AccountNumberEncryptionConverter.class)
    @Column(name = "account_number", nullable = false, length = 255)
    private String accountNumber;

    @Column(name = "account_holder", nullable = false, length = 50)
    private String accountHolder;

    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SellerBankAccountStatus status;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static SellerBankAccount create(
            Seller seller,
            String bankCode,
            String accountNumber,
            String accountHolder,
            boolean isPrimary) {
        if (seller == null || bankCode == null || accountNumber == null || accountHolder == null) {
            throw new IllegalArgumentException(
                    "SellerBankAccount 필수값 누락(seller·bankCode·accountNumber·accountHolder).");
        }
        SellerBankAccount account = new SellerBankAccount();
        account.seller = seller;
        account.bankCode = bankCode;
        account.accountNumber = accountNumber;
        account.accountHolder = accountHolder;
        account.isPrimary = isPrimary;
        account.status = SellerBankAccountStatus.PENDING;
        return account;
    }

    /** 계좌번호 끝 4자리(4자 이하면 전체). 정산 상세·셀러 상세 응답의 마스킹 규칙과 같다. */
    public String accountNumberSuffix() {
        String number = accountNumber == null ? "" : accountNumber;
        return number.length() <= SUFFIX_LENGTH ? number : number.substring(number.length() - SUFFIX_LENGTH);
    }

    /** 운영자 확인 완료 — VERIFIED·verifiedAt 기록(관리자 등록·수정 시·실명인증 연동은 이월·D-188). */
    public void markVerified(LocalDateTime verifiedAt) {
        if (verifiedAt == null) {
            throw new IllegalArgumentException("verifiedAt은 null일 수 없습니다.");
        }
        this.status = SellerBankAccountStatus.VERIFIED;
        this.verifiedAt = verifiedAt;
    }

    /**
     * 계좌 정보 in-place 수정(정산 미참조 행만 — 참조 여부는 서비스가 판정).
     *
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public void update(String bankCode, String accountNumber, String accountHolder) {
        if (bankCode == null || accountNumber == null || accountHolder == null) {
            throw new IllegalArgumentException("SellerBankAccount 수정 필수값 누락(bankCode·accountNumber·accountHolder).");
        }
        this.bankCode = bankCode;
        this.accountNumber = accountNumber;
        this.accountHolder = accountHolder;
    }

    /** 주 계좌 지정. 같은 셀러의 기존 주 계좌 해제는 서비스가 벌크 UPDATE로 먼저 수행한다(UNIQUE·flush 순서·D-188). */
    public void markPrimary() {
        this.isPrimary = true;
    }
}
