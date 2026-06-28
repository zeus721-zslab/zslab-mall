package com.zslab.mall.seller.entity;

import com.zslab.mall.common.entity.AbstractFullAuditableEntity;
import com.zslab.mall.seller.enums.SellerBankAccountStatus;
import jakarta.persistence.Column;
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
 * accountNumber는 현 단계 평문 String 매핑·AES @Converter는 Track 8+ 이연(SLR-2 D-85 Q3).
 * deleted_at 없음(ARCHIVE 분류) — soft-delete 미적용.
 */
@Entity
@Table(name = "seller_bank_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerBankAccount extends AbstractFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false, updatable = false)
    private Seller seller;

    @Column(name = "bank_code", nullable = false, length = 20)
    private String bankCode;

    /** Track 8+에서 AES @Converter 적용 예정(SLR-2·D-23 B-d4). 현 단계 평문 저장. */
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
}
