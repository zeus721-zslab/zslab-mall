package com.zslab.mall.claim.controller;

import com.zslab.mall.claim.controller.response.ClaimResponse;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller 액터용 Claim REST 컨트롤러(D-92). 승인·거부 2 endpoint를 노출한다(D-92 Q6 α).
 *
 * <p>URL은 액터 중립이다(D-92 Q4 β): base path {@code /api/v1/claims}를 {@link BuyerClaimController}와 공존하며
 * HTTP method·하위 경로(/approve·/reject)로 분리한다(Buyer는 동일 경로에 POST/GET을 두지 않아 충돌 없음). Seller
 * 식별은 {@code X-Seller-Id} 헤더 stub이다(D-92 Q1 α′·{@link SellerActorResolver}).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 액터 해소·publicId→id 해소·Service 위임·응답 조립만 수행하며 권한/상태 판단은
 * {@link ClaimService} 책임이다. approve/reject primitive는 void이므로 전이 후 재조회로 응답을 조립한다.
 */
@RestController
@RequestMapping("/api/v1/claims")
public class SellerClaimController {

    private final ClaimService claimService;
    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final SellerActorResolver sellerActorResolver;

    public SellerClaimController(
            ClaimService claimService,
            ClaimRepository claimRepository,
            OrderItemRepository orderItemRepository,
            SellerActorResolver sellerActorResolver) {
        this.claimService = claimService;
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.sellerActorResolver = sellerActorResolver;
    }

    /** Seller 클레임 승인. 미존재·권한 위반 모두 404(정보 노출 회피·D-92 Q3). 성공 시 200 + 갱신된 ClaimResponse. */
    @PostMapping("/{claimPublicId}/approve")
    public ClaimResponse approveBySeller(@PathVariable String claimPublicId, HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: publicId=" + claimPublicId));
        claimService.approveBySeller(claim.getId(), sellerId, LocalDateTime.now());
        return toResponse(claimPublicId);
    }

    /** Seller 클레임 거부. 미존재·권한 위반 모두 404(정보 노출 회피·D-92 Q3). 성공 시 200 + 갱신된 ClaimResponse. */
    @PostMapping("/{claimPublicId}/reject")
    public ClaimResponse rejectBySeller(@PathVariable String claimPublicId, HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: publicId=" + claimPublicId));
        claimService.rejectBySeller(claim.getId(), sellerId, LocalDateTime.now());
        return toResponse(claimPublicId);
    }

    /**
     * 전이 후 Claim을 재조회해 응답을 조립한다. approve/reject primitive가 void이므로 갱신 상태 반영을 위해 re-fetch한다.
     * orderItemPublicId는 OrderItem.id → public_id로 해소한다(ClaimResponse enrich 패턴 정합).
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
