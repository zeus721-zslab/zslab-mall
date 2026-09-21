package com.zslab.mall.seller.service;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.seller.controller.request.SellerBankAccountRegisterRequest;
import com.zslab.mall.seller.controller.response.SellerBankAccountResponse;
import com.zslab.mall.seller.entity.SellerBankAccount;
import com.zslab.mall.seller.exception.SellerOwnerRequiredException;
import com.zslab.mall.seller.repository.SellerBankAccountRepository;
import com.zslab.mall.seller.repository.SellerUserRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 본인 정산계좌(Track 90-D-3·D-199): 조회·등록. 셀러 식별·상태 가드(SUSPENDED 쓰기 403·PENDING/TERMINATED 401)는 컨트롤러가 호출하는
 * {@code SellerActorResolver}가 끝낸 뒤 들어온다. 등록 규칙은 관리자와 같아 {@link AdminSellerBankAccountCommandService}의 sellerId 오버로드에
 * 위임한다(첫 계좌 자동 주 계좌·VERIFIED·감사 CREATE). 수정·삭제·주 계좌 전환은 관리자 전용이라 여기 없다.
 */
@Service
@Transactional
public class SellerBankAccountService {

    private final SellerBankAccountRepository sellerBankAccountRepository;
    private final SellerUserRepository sellerUserRepository;
    private final AdminSellerBankAccountCommandService commandService;

    public SellerBankAccountService(
            SellerBankAccountRepository sellerBankAccountRepository,
            SellerUserRepository sellerUserRepository,
            AdminSellerBankAccountCommandService commandService) {
        this.sellerBankAccountRepository = sellerBankAccountRepository;
        this.sellerUserRepository = sellerUserRepository;
        this.commandService = commandService;
    }

    /** 본인 셀러 계좌 전부(등록순). 모든 셀러 역할이 조회할 수 있다. */
    @Transactional(readOnly = true)
    public List<SellerBankAccountResponse> list(Long sellerId) {
        return sellerBankAccountRepository.findAllBySellerId(sellerId).stream()
                .map(SellerBankAccountResponse::of)
                .toList();
    }

    /**
     * 계좌 등록 — SELLER_OWNER만. JWT는 coarse SELLER만 실으므로 세분 역할은 seller_user·role 조회로 요청마다 판정한다(D-199).
     *
     * @throws SellerOwnerRequiredException 호출자가 이 셀러의 SELLER_OWNER 구성원이 아닌 경우(403)
     * @throws com.zslab.mall.seller.exception.SellerNotFoundException 셀러 미존재(404·인증 직후 삭제 경합)
     */
    public SellerBankAccountResponse register(
            Long sellerId, Long userId, SellerBankAccountRegisterRequest request, AuditContext auditContext) {
        if (!sellerUserRepository.existsBySellerIdAndUserIdAndRoleCode(sellerId, userId, RoleCode.SELLER_OWNER)) {
            throw new SellerOwnerRequiredException("정산계좌 등록은 셀러 대표(SELLER_OWNER)만 할 수 있습니다: sellerId=" + sellerId
                    + " userId=" + userId);
        }
        SellerBankAccount account = commandService.register(
                sellerId, request.bankCode(), request.accountNumber(), request.accountHolder(), auditContext);
        return SellerBankAccountResponse.of(account);
    }
}
