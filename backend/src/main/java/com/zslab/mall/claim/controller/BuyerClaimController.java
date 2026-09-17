package com.zslab.mall.claim.controller;

import com.zslab.mall.claim.controller.request.ClaimRequestRequest;
import com.zslab.mall.claim.controller.request.ReturnShipmentRequest;
import com.zslab.mall.claim.controller.response.ClaimAttachmentUploadResponse;
import com.zslab.mall.claim.controller.response.ClaimResponse;
import com.zslab.mall.claim.controller.response.ClaimSummaryResponse;
import com.zslab.mall.claim.controller.response.ReturnShipmentResponse;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.service.ClaimAttachmentService;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.auth.BuyerActorResolver;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.order.controller.response.PagedResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
    private final ClaimAttachmentService claimAttachmentService;
    private final BuyerActorResolver buyerActorResolver;

    public BuyerClaimController(ClaimService claimService, ClaimAttachmentService claimAttachmentService,
            BuyerActorResolver buyerActorResolver) {
        this.claimService = claimService;
        this.claimAttachmentService = claimAttachmentService;
        this.buyerActorResolver = buyerActorResolver;
    }

    /**
     * 반품 회수 송장 등록(Track 81-A D-170·R4). 본인·RETURN·APPROVED·회수 전 클레임만. 200 + 회수 Delivery 요약.
     * 미존재·타인 404·유형/상태/중복 422·형식 400.
     */
    @PostMapping("/{claimPublicId}/return-shipment")
    public ResponseEntity<ReturnShipmentResponse> registerReturnShipment(
            @PathVariable String claimPublicId,
            @RequestBody @Valid ReturnShipmentRequest request,
            HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        Delivery delivery = claimService.registerReturnShipmentByBuyer(claimPublicId, buyerId, request.carrier(), request.trackingNo());
        return ResponseEntity.ok(ReturnShipmentResponse.from(delivery));
    }

    /**
     * 반품 사진 업로드(Track 81-B). multipart 필드명 {@code files}(다중·jpg/png/webp·파일당 10MB·최대 5장). 항상 200·파일별 결과이며
     * 성공 항목의 attachmentId를 클레임 요청 본문 attachmentIds에 넘긴다. 인가는 SecurityConfig {@code /api/v1/claims/**}→BUYER.
     */
    @PostMapping(value = "/attachments", consumes = "multipart/form-data")
    public ResponseEntity<ClaimAttachmentUploadResponse> uploadAttachments(
            @RequestPart("files") List<MultipartFile> files, HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        return ResponseEntity.ok(claimAttachmentService.upload(buyerId, files));
    }

    /** 클레임 요청(CANCEL 한정·Q6). 신규 생성 201 + Location. requestedAt은 서버 시각으로 채운다. */
    @PostMapping
    public ResponseEntity<ClaimResponse> request(
            @RequestBody @Valid ClaimRequestRequest request, HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        Claim claim = claimService.request(request.toCommand(buyerId, LocalDateTime.now()));
        ClaimResponse response = ClaimResponse.from(claim, request.orderItemPublicId(),
                claimAttachmentService.urlsOf(claim.getId()));
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
