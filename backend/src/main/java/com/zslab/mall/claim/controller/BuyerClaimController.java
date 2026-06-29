package com.zslab.mall.claim.controller;

import com.zslab.mall.claim.controller.request.ClaimRequestRequest;
import com.zslab.mall.claim.controller.response.ClaimResponse;
import com.zslab.mall.claim.controller.response.ClaimSummaryResponse;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.exception.UnauthenticatedException;
import com.zslab.mall.order.controller.response.PagedResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDateTime;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Buyer 클레임 REST 컨트롤러(D-40·URL 액터 중립 /api/v1/claims). 요청·단건·목록 3 엔드포인트를 노출한다(D-89 Q4).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 인증 헤더 해소·Service 위임·HTTP 변환만 수행하며 Repository 직접 접근·트랜잭션 제어·
 * 도메인 규칙 판단을 하지 않는다(소유권·CANCEL 한정·CLM-5 검증은 {@link ClaimService} 책임·Q8).
 *
 * <p>임시 인증(D-39): {@code X-Buyer-Id} 헤더(BIGINT). 누락 401·형식 오류 400(BuyerOrderController 패턴 1:1).
 * 단건 path 변수는 별도 형식 검증을 두지 않으며, 미매칭은 조회 단계에서 404로 처리한다(정보 노출 회피·BuyerOrderController 정합).
 */
@RestController
@RequestMapping("/api/v1/claims")
public class BuyerClaimController {

    private static final String BUYER_ID_HEADER = "X-Buyer-Id";

    private final ClaimService claimService;

    public BuyerClaimController(ClaimService claimService) {
        this.claimService = claimService;
    }

    /** 클레임 요청(CANCEL 한정·Q6). 신규 생성 201 + Location. requestedAt은 서버 시각으로 채운다. */
    @PostMapping
    public ResponseEntity<ClaimResponse> request(
            @RequestHeader(value = BUYER_ID_HEADER, required = false) String buyerIdHeader,
            @RequestBody @Valid ClaimRequestRequest request) {
        Long buyerId = resolveBuyerId(buyerIdHeader);
        Claim claim = claimService.request(request.toCommand(buyerId, LocalDateTime.now()));
        ClaimResponse response = ClaimResponse.from(claim, request.orderItemPublicId());
        return ResponseEntity.created(URI.create("/api/v1/claims/" + claim.getPublicId())).body(response);
    }

    /** 본인 클레임 단건 조회. 미존재·타인 클레임 모두 404(정보 노출 회피·Q8). */
    @GetMapping("/{claimPublicId}")
    public ResponseEntity<ClaimResponse> getOne(
            @RequestHeader(value = BUYER_ID_HEADER, required = false) String buyerIdHeader,
            @PathVariable String claimPublicId) {
        Long buyerId = resolveBuyerId(buyerIdHeader);
        return ResponseEntity.ok(claimService.getClaim(claimPublicId, buyerId));
    }

    /** 본인 클레임 목록(requested_by 기준·D-54 PagedResponse·page/size 클램프는 Service). */
    @GetMapping
    public ResponseEntity<PagedResponse<ClaimSummaryResponse>> list(
            @RequestHeader(value = BUYER_ID_HEADER, required = false) String buyerIdHeader,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long buyerId = resolveBuyerId(buyerIdHeader);
        return ResponseEntity.ok(claimService.listClaims(buyerId, page, size));
    }

    /** X-Buyer-Id 헤더를 BIGINT로 해소한다. 누락 → 401, 파싱 실패 → 400(BuyerOrderController 패턴 1:1). */
    private Long resolveBuyerId(String buyerIdHeader) {
        if (buyerIdHeader == null || buyerIdHeader.isBlank()) {
            throw new UnauthenticatedException("X-Buyer-Id 헤더가 필요합니다.");
        }
        try {
            return Long.parseLong(buyerIdHeader.trim());
        } catch (NumberFormatException e) {
            throw new MalformedRequestException("X-Buyer-Id 형식이 올바르지 않습니다: " + buyerIdHeader);
        }
    }
}
