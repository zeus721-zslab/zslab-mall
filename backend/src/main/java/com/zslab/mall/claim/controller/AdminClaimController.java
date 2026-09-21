package com.zslab.mall.claim.controller;

import com.zslab.mall.claim.controller.request.AdminClaimActionFilter;
import com.zslab.mall.claim.controller.request.AdminClaimSort;
import com.zslab.mall.claim.controller.request.ClaimApproveRequest;
import com.zslab.mall.claim.controller.request.ClaimInspectRequest;
import com.zslab.mall.claim.controller.request.ClaimRejectRequest;
import com.zslab.mall.claim.controller.response.AdminClaimListResponse;
import com.zslab.mall.claim.controller.response.ClaimResponse;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.service.AdminClaimQueryService;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.refund.enums.RefundStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 Claim REST 컨트롤러(Track 10-B·D-93). 승인·거부 2 endpoint를 노출한다(D-93 Q8 α). Track 80(D-169)에서 목록 GET을 추가했다.
 *
 * <p>URL은 {@code /api/v1/admin/claims} prefix를 사용한다(D-93 Q6 γ′). D-40 본문은 명시 prefix 2건
 * ({@code /buyer}·{@code /seller})만 금지하며 {@code /admin}은 명시 부재로, {@code SellerClaimController}(Track 92 제거)의
 * {@code /api/v1/claims} base path와 라우팅 충돌을 회피한다(WARN-1 해소·SellerClaimController 무변경). Admin 식별은
 * {@code X-Admin-Id} 헤더 stub이다(D-93 Q1 α·{@link AdminActorResolver}).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 액터 해소·publicId→id 해소·Service 위임·응답 조립만 수행한다. Admin은 전체 접근이므로
 * 권한 검증 단락이 부재하며(D-93 Q3·Q5) Claim 미존재만 404다. approve/reject primitive는 void이므로 전이 후 재조회로
 * 응답을 조립한다({@code SellerClaimController#toResponse}(Track 92 제거) 패턴 1:1·ClaimResponse 재사용·D-93 Q7 α).
 */
@RestController
@RequestMapping("/api/v1/admin/claims")
public class AdminClaimController {

    private final ClaimService claimService;
    private final AdminClaimQueryService adminClaimQueryService;
    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final AdminActorResolver adminActorResolver;

    public AdminClaimController(
            ClaimService claimService,
            AdminClaimQueryService adminClaimQueryService,
            ClaimRepository claimRepository,
            OrderItemRepository orderItemRepository,
            AdminActorResolver adminActorResolver) {
        this.claimService = claimService;
        this.adminClaimQueryService = adminClaimQueryService;
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.adminActorResolver = adminActorResolver;
    }

    /**
     * 관리자 클레임 목록(Track 80 D-169·C7). 필터: type(유형 탭·null=전체)·status·refundStatus(최신 환불 상태·Track 89-A)·from/to(requested_at·ISO-8601)·buyerPublicId(회원 정확·Track 84·미존재는 빈 페이지)·keyword(주문번호 정확·
     * 구매자 이름/이메일·상품명 부분). 허용 외 enum·sort 값 400, keyword 50자 초과·from&gt;to 400(MALFORMED_REQUEST). 인가는
     * SecurityConfig {@code /api/v1/admin/**}→hasRole(ADMIN)이 강제한다.
     */
    @GetMapping
    public ResponseEntity<AdminClaimListResponse> list(
            @RequestParam(required = false) ClaimType type,
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) RefundStatus refundStatus,
            @RequestParam(required = false) AdminClaimActionFilter action,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String buyerPublicId,
            @RequestParam(defaultValue = "LATEST") AdminClaimSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adminClaimQueryService.listClaims(type, status, refundStatus, action, keyword, from, to, buyerPublicId, sort, page, size));
    }

    /**
     * Admin 클레임 승인. 미존재만 404(전체 접근·D-93 Q5). 성공 시 200 + 갱신된 ClaimResponse.
     *
     * <p>EXCHANGE 차액환불(D-115): body는 선택이며(required=false) 부재 시 refundAmount=null(차액 없음·기존 동작).
     */
    @PostMapping("/{claimPublicId}/approve")
    public ClaimResponse approveByAdmin(@PathVariable String claimPublicId,
            @RequestBody(required = false) ClaimApproveRequest body, HttpServletRequest request) {
        // X-Admin-Id 존재·형식 검증만 수행한다(전체 접근·식별자 미사용·D-93 Q3). 누락 401·형식 오류 400.
        adminActorResolver.resolve(request);
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: publicId=" + claimPublicId));
        Long refundAmount = body != null ? body.refundAmount() : null;
        claimService.approveByAdmin(claim.getId(), LocalDateTime.now(), refundAmount);
        return toResponse(claimPublicId);
    }

    /**
     * Admin 클레임 거부. 미존재만 404(전체 접근·D-93 Q5). 성공 시 200 + 갱신된 ClaimResponse.
     *
     * <p>거부 사유 코드 필수·메모 선택(Track 80 D-169). body 누락·사유 누락 400.
     */
    @PostMapping("/{claimPublicId}/reject")
    public ClaimResponse rejectByAdmin(@PathVariable String claimPublicId,
            @RequestBody @Valid ClaimRejectRequest body, HttpServletRequest request) {
        // X-Admin-Id 존재·형식 검증만 수행한다(전체 접근·식별자 미사용·D-93 Q3). 누락 401·형식 오류 400.
        adminActorResolver.resolve(request);
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: publicId=" + claimPublicId));
        claimService.rejectByAdmin(claim.getId(), body.reasonCode(), body.memo(), LocalDateTime.now());
        return toResponse(claimPublicId);
    }

    /**
     * Admin 반품 회수 확인(Track 81-A D-170). 회수 송장(RETURN Delivery) 선행 필요(부재 422). 미존재 404·APPROVED 아님 422.
     */
    @PostMapping("/{claimPublicId}/confirm-pickup")
    public ClaimResponse confirmPickupByAdmin(@PathVariable String claimPublicId, HttpServletRequest request) {
        adminActorResolver.resolve(request);
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: publicId=" + claimPublicId));
        claimService.confirmPickupByAdmin(claim.getId(), LocalDateTime.now());
        return toResponse(claimPublicId);
    }

    /**
     * Admin 반품 검수(Track 81-A D-170·R5). PASS{restock} → 환불 개시 / FAIL{rejectReasonCode·memo·reshipCarrier·reshipTrackingNo} → 거부·재발송.
     * 미존재 404·상태/중복 422·조건부 필수 누락 400.
     */
    @PostMapping("/{claimPublicId}/inspect")
    public ClaimResponse inspectByAdmin(@PathVariable String claimPublicId,
            @RequestBody @Valid ClaimInspectRequest body, HttpServletRequest request) {
        adminActorResolver.resolve(request);
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: publicId=" + claimPublicId));
        claimService.inspectByAdmin(claim.getId(), body.result(), body.restock(), body.rejectReasonCode(), body.memo(),
                body.reshipCarrier(), body.reshipTrackingNo(), LocalDateTime.now());
        return toResponse(claimPublicId);
    }

    /**
     * 전이 후 Claim을 재조회해 응답을 조립한다. approve/reject primitive가 void이므로 갱신 상태 반영을 위해 re-fetch한다.
     * orderItemPublicId는 OrderItem.id → public_id로 해소한다({@code SellerClaimController#toResponse}(Track 92 제거) 패턴 1:1).
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
