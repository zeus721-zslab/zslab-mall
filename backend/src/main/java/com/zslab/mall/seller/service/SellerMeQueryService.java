package com.zslab.mall.seller.service;

import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.common.exception.UnauthenticatedException;
import com.zslab.mall.seller.controller.response.SellerMeResponse;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.entity.SellerUser;
import com.zslab.mall.seller.repository.SellerBankAccountRepository;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.seller.repository.SellerUserRepository;
import com.zslab.mall.settlement.enums.SettlementStatus;
import com.zslab.mall.settlement.repository.SettlementRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 본인 조회(Track 90-B-1·read-only). 셀러·구성원·상태 판정은 {@code SellerActorResolver}가 이미 끝냈으므로(sellerId 확정)
 * 여기서는 표시 정보만 조립한다. 조회 4건(seller·seller_user·role·정산 건수) + 계좌 1건 = 고정 5쿼리.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerMeQueryService {

    private static final Set<SettlementStatus> PENDING_ONLY = Set.of(SettlementStatus.PENDING);

    private final SellerRepository sellerRepository;
    private final SellerUserRepository sellerUserRepository;
    private final RoleRepository roleRepository;
    private final SettlementRepository settlementRepository;
    private final SellerBankAccountRepository sellerBankAccountRepository;

    /**
     * @throws UnauthenticatedException resolver 통과 직후 셀러·구성원 행이 사라진 경합(soft-delete·제거) — 인증 무효와 같은 401
     */
    public SellerMeResponse getMe(Long sellerId, Long userId) {
        Seller seller = sellerRepository.findById(sellerId)
                .orElseThrow(() -> new UnauthenticatedException("인증된 판매자를 확인할 수 없습니다"));
        SellerUser membership = sellerUserRepository.findBySellerIdAndUserId(sellerId, userId)
                .orElseThrow(() -> new UnauthenticatedException("인증된 판매자를 확인할 수 없습니다"));
        Role role = roleRepository.findById(membership.getRoleId())
                .orElseThrow(() -> new IllegalStateException("셀러 구성원 역할을 찾을 수 없습니다: roleId=" + membership.getRoleId()));
        long pendingSettlementCount = settlementRepository.countBySellerIdAndStatusIn(sellerId, PENDING_ONLY);
        boolean bankAccountRegistered = !sellerBankAccountRepository.findPrimaryBankAccountIds(sellerId).isEmpty();
        return new SellerMeResponse(seller.getPublicId(), seller.getCompanyName(), seller.getStatus(), role.getCode(),
                pendingSettlementCount, bankAccountRegistered);
    }
}
