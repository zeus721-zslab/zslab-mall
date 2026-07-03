package com.zslab.mall.claim.controller;

import com.zslab.mall.claim.controller.request.ClaimRequestRequest;
import com.zslab.mall.claim.controller.response.ClaimResponse;
import com.zslab.mall.claim.controller.response.ClaimSummaryResponse;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.auth.BuyerActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDateTime;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Buyer 클레임 REST 컨트롤러(D-40·URL 액터 중립 /api/v1/claims). 요청·단건·목록 3 엔드포인트를 노출한다(D-89 Q4).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 인증 액터 해소·Service 위임·HTTP 변환만 수행하며 Repository 직접 접근·트랜잭션 제어·
 * 도메인 규칙 판단을 하지 않는다(소유권·CANCEL 한정·CLM-5 검증은 {@link ClaimService} 책임·Q8).
 *
 * <p>인증(Track 31 Phase 3): {@link BuyerActorResolver}가 SecurityContext에서 buyerId를 해소한다. 미인증은 Security
 * 필터가 경로 hasRole(BUYER)로 401 선차단하며, 소유권 불일치는 조회 단계에서 404로 처리한다(정보 노출 회피).
 */
@RestController
@RequestMapping("/api/v1/claims")
public class BuyerClaimController {

    private final ClaimService claimService;
    private final BuyerActorResolver buyerActorResolver;

    public BuyerClaimController(ClaimService claimService, BuyerActorResolver buyerActorResolver) {
        this.claimService = claimService;
        this.buyerActorResolver = buyerActorResolver;
    }

    /** 클레임 요청(CANCEL 한정·Q6). 신규 생성 201 + Location. requestedAt은 서버 시각으로 채운다. */
    @PostMapping
    public ResponseEntity<ClaimResponse> request(
            @RequestBody @Valid ClaimRequestRequest request, HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        Claim claim = claimService.request(request.toCommand(buyerId, LocalDateTime.now()));
        ClaimResponse response = ClaimResponse.from(claim, request.orderItemPublicId());
        return ResponseEntity.created(URI.create("/api/v1/claims/" + claim.getPublicId())).body(response);
    }

    /** 본인 클레임 단건 조회. 미존재·타인 클레임 모두 404(정보 노출 회피·Q8). */
    @GetMapping("/{claimPublicId}")
    public ResponseEntity<ClaimResponse> getOne(
            @PathVariable String claimPublicId, HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        return ResponseEntity.ok(claimService.getClaim(claimPublicId, buyerId));
    }

    /** 본인 클레임 목록(requested_by 기준·D-54 PagedResponse·page/size 클램프는 Service). */
    @GetMapping
    public ResponseEntity<PagedResponse<ClaimSummaryResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        return ResponseEntity.ok(claimService.listClaims(buyerId, page, size));
    }
}
