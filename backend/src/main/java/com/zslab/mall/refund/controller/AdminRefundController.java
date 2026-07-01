package com.zslab.mall.refund.controller;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.refund.controller.request.AdminRefundInitiateRequest;
import com.zslab.mall.refund.controller.response.AdminRefundInitiateResponse;
import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.service.RefundService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 Refund REST 컨트롤러(Track 22·D-106). 운영자 수동 환불 개시 1 endpoint를 노출한다.
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드 절대경로를 부여한다(D-106 §3 옵션 A·{@link com.zslab.mall.inventory.controller.AdminInventoryController}
 * ·{@link com.zslab.mall.delivery.controller.AdminDeliveryController} 선례 정합). {@code AdminClaimController}는 클래스 base path를
 * 쓰나 본 컨트롤러는 옵션 A다. claimPublicId 리소스 축을 공유하되 initiate-refund는 full path가 상이해 라우팅 충돌이 없다.
 * Admin 식별은 {@code X-Admin-Id} 헤더 stub이다(D-93·{@link AdminActorResolver}).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 액터 해소·publicId→id 해소·Service 위임·응답 조립만 수행한다. Admin은 전체 접근이므로
 * 권한 검증 단락이 부재하며(D-93 Q3·Q5) claimPublicId 미존재만 404({@link ClaimNotFoundException})다.
 */
@RestController
public class AdminRefundController {

    private final RefundService refundService;
    private final ClaimRepository claimRepository;
    private final AdminActorResolver adminActorResolver;

    public AdminRefundController(
            RefundService refundService,
            ClaimRepository claimRepository,
            AdminActorResolver adminActorResolver) {
        this.refundService = refundService;
        this.claimRepository = claimRepository;
        this.adminActorResolver = adminActorResolver;
    }

    /**
     * Admin 수동 환불 개시(D-106 §4). 미존재 claimPublicId → 404. APPROVED 아닌 Claim → 422(CLM-3). amount ≤ 0 → 400.
     * 성공 시 200 + 개시된 Refund 응답. CLM-3·PAY-1·멱등 게이트·D-67 FAILED 전이는
     * {@link RefundService#initiateByAdmin} → {@code initiate} 도메인 위임 책임이다.
     */
    @PostMapping("/api/v1/admin/claims/{claimPublicId}/initiate-refund")
    public AdminRefundInitiateResponse initiateRefund(
            @PathVariable String claimPublicId,
            @RequestBody AdminRefundInitiateRequest request,
            HttpServletRequest httpRequest) {
        // X-Admin-Id 존재·형식 검증만 수행한다(전체 접근·식별자 미사용·D-93 Q3). 누락 401·형식 오류 400.
        adminActorResolver.resolve(httpRequest);
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: publicId=" + claimPublicId));
        // initiateByAdmin은 @Transactional 종료 후 개시된 Refund를 반환한다. 스칼라 필드만 읽으므로 OSIV off에서도 재조회 불요.
        Refund refund = refundService.initiateByAdmin(claim.getId(), request.amount());
        return AdminRefundInitiateResponse.from(claimPublicId, refund);
    }
}
