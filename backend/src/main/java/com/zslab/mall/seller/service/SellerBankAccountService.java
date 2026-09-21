package com.zslab.mall.seller.service;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.seller.controller.request.SellerBankAccountRegisterRequest;
import com.zslab.mall.seller.controller.response.SellerBankAccountResponse;
import com.zslab.mall.seller.entity.SellerBankAccount;
import com.zslab.mall.seller.exception.SellerNotFoundException;
import com.zslab.mall.seller.exception.SellerOwnerRequiredException;
import com.zslab.mall.seller.repository.SellerBankAccountRepository;
import com.zslab.mall.seller.repository.SellerRepository;
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
    private final SellerRepository sellerRepository;
    private final SellerUserRepository sellerUserRepository;
    private final AdminSellerBankAccountCommandService commandService;

    public SellerBankAccountService(
            SellerBankAccountRepository sellerBankAccountRepository,
            SellerRepository sellerRepository,
            SellerUserRepository sellerUserRepository,
            AdminSellerBankAccountCommandService commandService) {
        this.sellerBankAccountRepository = sellerBankAccountRepository;
        this.sellerRepository = sellerRepository;
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
     * <p><b>판정 순서(외부 검토 Q2)</b>: 셀러 행 비관락({@code findByIdForUpdate})을 먼저 잡고 그 뒤에 OWNER를 판정한다. 관리자의 구성원
     * 제거·역할 변경({@code AdminSellerMemberCommandService.remove·changeRole})이 같은 셀러 행 락({@code findByPublicIdForUpdate}) 안에서
     * 실행되므로, 락 이후 판정은 강등·제거가 커밋된 뒤의 seller_user를 본다(락 전 판정이면 "판정 통과 → 강등 커밋 → 등록"이 가능).
     * 락 전에는 이 트랜잭션에서 다른 조회를 하지 않는다. 이어지는 {@code commandService.register(sellerId, …)}가 같은 행을 다시
     * FOR UPDATE 하는 것은 같은 트랜잭션이라 무해하다 — 이를 피하려고 락 없는 공개 API를 늘리지 않는다.
     *
     * @throws SellerNotFoundException 셀러 미존재(404·인증 직후 삭제 경합)
     * @throws SellerOwnerRequiredException 호출자가 이 셀러의 SELLER_OWNER 구성원이 아닌 경우(403)
     */
    public SellerBankAccountResponse register(
            Long sellerId, Long userId, SellerBankAccountRegisterRequest request, AuditContext auditContext) {
        sellerRepository.findByIdForUpdate(sellerId)
                .orElseThrow(() -> new SellerNotFoundException("셀러를 찾을 수 없습니다: sellerId=" + sellerId));
        if (!sellerUserRepository.existsBySellerIdAndUserIdAndRoleCode(sellerId, userId, RoleCode.SELLER_OWNER)) {
            throw new SellerOwnerRequiredException("정산계좌 등록은 셀러 대표(SELLER_OWNER)만 할 수 있습니다: sellerId=" + sellerId
                    + " userId=" + userId);
        }
        SellerBankAccount account = commandService.register(
                sellerId, request.bankCode(), request.accountNumber(), request.accountHolder(), auditContext);
        return SellerBankAccountResponse.of(account);
    }
}
