package com.zslab.mall.claim.controller;

import com.zslab.mall.claim.controller.response.ClaimResponse;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 Claim REST 컨트롤러(Track 10-B·D-93). 승인·거부 2 endpoint를 노출한다(D-93 Q8 α).
 *
 * <p>URL은 {@code /api/v1/admin/claims} prefix를 사용한다(D-93 Q6 γ′). D-40 본문은 명시 prefix 2건
 * ({@code /buyer}·{@code /seller})만 금지하며 {@code /admin}은 명시 부재로, {@link SellerClaimController}의
 * {@code /api/v1/claims} base path와 라우팅 충돌을 회피한다(WARN-1 해소·SellerClaimController 무변경). Admin 식별은
 * {@code X-Admin-Id} 헤더 stub이다(D-93 Q1 α·{@link AdminActorResolver}).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 액터 해소·publicId→id 해소·Service 위임·응답 조립만 수행한다. Admin은 전체 접근이므로
 * 권한 검증 단락이 부재하며(D-93 Q3·Q5) Claim 미존재만 404다. approve/reject primitive는 void이므로 전이 후 재조회로
 * 응답을 조립한다({@link SellerClaimController#toResponse 패턴 1:1}·ClaimResponse 재사용·D-93 Q7 α).
 */
@RestController
@RequestMapping("/api/v1/admin/claims")
public class AdminClaimController {

    private final ClaimService claimService;
    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final AdminActorResolver adminActorResolver;

    public AdminClaimController(
            ClaimService claimService,
            ClaimRepository claimRepository,
            OrderItemRepository orderItemRepository,
            AdminActorResolver adminActorResolver) {
        this.claimService = claimService;
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.adminActorResolver = adminActorResolver;
    }

    /** Admin 클레임 승인. 미존재만 404(전체 접근·D-93 Q5). 성공 시 200 + 갱신된 ClaimResponse. */
    @PostMapping("/{claimPublicId}/approve")
    public ClaimResponse approveByAdmin(@PathVariable String claimPublicId, HttpServletRequest request) {
        // X-Admin-Id 존재·형식 검증만 수행한다(전체 접근·식별자 미사용·D-93 Q3). 누락 401·형식 오류 400.
        adminActorResolver.resolve(request);
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: publicId=" + claimPublicId));
        claimService.approveByAdmin(claim.getId(), LocalDateTime.now());
        return toResponse(claimPublicId);
    }

    /** Admin 클레임 거부. 미존재만 404(전체 접근·D-93 Q5). 성공 시 200 + 갱신된 ClaimResponse. */
    @PostMapping("/{claimPublicId}/reject")
    public ClaimResponse rejectByAdmin(@PathVariable String claimPublicId, HttpServletRequest request) {
        // X-Admin-Id 존재·형식 검증만 수행한다(전체 접근·식별자 미사용·D-93 Q3). 누락 401·형식 오류 400.
        adminActorResolver.resolve(request);
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: publicId=" + claimPublicId));
        claimService.rejectByAdmin(claim.getId(), LocalDateTime.now());
        return toResponse(claimPublicId);
    }

    /**
     * 전이 후 Claim을 재조회해 응답을 조립한다. approve/reject primitive가 void이므로 갱신 상태 반영을 위해 re-fetch한다.
     * orderItemPublicId는 OrderItem.id → public_id로 해소한다({@link SellerClaimController#toResponse} 패턴 1:1).
     */
    private ClaimResponse toResponse(String claimPublicId) {
        Claim refreshed = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new IllegalStateException("Claim 전이 후 재조회 실패: publicId=" + claimPublicId));
        String orderItemPublicId = orderItemRepository.findById(refreshed.getOrderItemId())
                .map(OrderItem::getPublicId)
                .orElseThrow(() -> new IllegalStateException(
                        "OrderItem 무결성 위반: orderItemId=" + refreshed.getOrderItemId()));
        return ClaimResponse.from(refreshed, orderItemPublicId);
    }
}
