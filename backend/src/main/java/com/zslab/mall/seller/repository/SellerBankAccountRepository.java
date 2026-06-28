package com.zslab.mall.seller.repository;

import com.zslab.mall.seller.entity.SellerBankAccount;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 판매자 정산계좌 Repository.
 */
public interface SellerBankAccountRepository extends JpaRepository<SellerBankAccount, Long> {
}
