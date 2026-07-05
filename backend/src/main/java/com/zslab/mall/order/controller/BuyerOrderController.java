package com.zslab.mall.order.controller;

import com.zslab.mall.checkout.controller.CheckoutOutcomeSupport;
import com.zslab.mall.checkout.service.CheckoutOutcome;
import com.zslab.mall.checkout.service.CheckoutService;
import com.zslab.mall.common.auth.BuyerActorResolver;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.order.controller.request.CreateOrderRequest;
import com.zslab.mall.order.controller.request.RetryPaymentRequest;
import com.zslab.mall.order.controller.response.CheckoutResponse;
import com.zslab.mall.order.controller.response.ConfirmPurchaseResponse;
import com.zslab.mall.order.controller.response.OrderResponse;
import com.zslab.mall.order.controller.response.OrderSummaryResponse;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.service.BuyerOrderConfirmService;
import com.zslab.mall.order.service.BuyerOrderQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.regex.Pattern;
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
 * Buyer 주문 REST 컨트롤러(D-40·§1). 4개 엔드포인트(생성·단건·목록·재결제)를 노출한다. URL은 액터 중립(/api/v1/orders).
 *
 * <p>HTTP 책임만 가진다(D-43.11): 인증 액터 해소·Service 위임·HTTP 변환만 수행하며 Repository 직접 접근·트랜잭션 제어·
 * 결제 규칙 판단을 하지 않는다. 조립은 {@link CheckoutService}(쓰기), 조회 enrich는 {@link BuyerOrderQueryService}가 담당한다.
 *
 * <p>인증(Track 31 Phase 3): {@link BuyerActorResolver}가 SecurityContext에서 buyerId를 해소한다. 미인증은 Security
 * 필터가 경로 hasRole(BUYER)로 401 선차단하며, 대상 불일치는 404다(§2). Idempotency-Key는 별도 헤더로 유지한다(§8).
 */
@RestController
@RequestMapping("/api/v1/orders")
public class BuyerOrderController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** Idempotency-Key 형식 검증(ULID/UUID 허용 문자·최대 128자·§8). */
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[0-9A-Za-z-]{1,128}$");

    private final CheckoutService checkoutService;
    private final BuyerOrderQueryService buyerOrderQueryService;
    private final BuyerOrderConfirmService buyerOrderConfirmService;
    private final BuyerActorResolver buyerActorResolver;

    public BuyerOrderController(
            CheckoutService checkoutService,
            BuyerOrderQueryService buyerOrderQueryService,
            BuyerOrderConfirmService buyerOrderConfirmService,
            BuyerActorResolver buyerActorResolver) {
        this.checkoutService = checkoutService;
        this.buyerOrderQueryService = buyerOrderQueryService;
        this.buyerOrderConfirmService = buyerOrderConfirmService;
        this.buyerActorResolver = buyerActorResolver;
    }

    /** 주문 생성 + 첫 결제 시작(§5). 신규/재초기화 201(+Location)·멱등성 캐시 200. */
    @PostMapping
    public ResponseEntity<CheckoutResponse> create(
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKeyHeader,
            @RequestBody @Valid CreateOrderRequest request, HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        String idempotencyKey = resolveIdempotencyKey(idempotencyKeyHeader);
        CheckoutOutcome outcome = checkoutService.checkout(request.toCommand(buyerId, idempotencyKey));
        return CheckoutOutcomeSupport.toResponseEntity(outcome);
    }

    /** 본인 주문 단건 조회(§11 seller 그룹화). */
    @GetMapping("/{orderPublicId}")
    public ResponseEntity<OrderResponse> getOne(
            @PathVariable String orderPublicId, HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        return ResponseEntity.ok(buyerOrderQueryService.getOrder(orderPublicId, buyerId));
    }

    /** 본인 주문 목록(D-54 PagedResponse·ordered_at DESC·sort 미노출). */
    @GetMapping
    public ResponseEntity<PagedResponse<OrderSummaryResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        return ResponseEntity.ok(buyerOrderQueryService.listOrders(buyerId, page, size));
    }

    /** 본인 주문 품목 구매확정(Track 47). 배송완료(DELIVERED)→구매확정(CONFIRMED) 전이 후 확정 결과 200. */
    @PostMapping("/{orderPublicId}/items/{orderItemPublicId}/confirm")
    public ResponseEntity<ConfirmPurchaseResponse> confirmPurchase(
            @PathVariable String orderPublicId,
            @PathVariable String orderItemPublicId,
            HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        return ResponseEntity.ok(ConfirmPurchaseResponse.from(
                buyerOrderConfirmService.confirmPurchase(buyerId, orderPublicId, orderItemPublicId)));
    }

    /** 재결제(§6). 재검증 2종(D-60) 후 신규 Payment 생성·201(Location=payment). */
    @PostMapping("/{orderPublicId}/payments")
    public ResponseEntity<CheckoutResponse> retryPayment(
            @PathVariable String orderPublicId,
            @RequestBody @Valid RetryPaymentRequest request, HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        CheckoutOutcome outcome = checkoutService.retryPayment(orderPublicId, buyerId, request.method());
        return CheckoutOutcomeSupport.toResponseEntity(outcome);
    }

    /** Idempotency-Key 헤더를 검증한다. 미전달은 null 허용(§8), 전달 시 형식 위반 → 400. */
    private String resolveIdempotencyKey(String idempotencyKeyHeader) {
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
            return null;
        }
        if (!IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKeyHeader).matches()) {
            throw new MalformedRequestException("Idempotency-Key 형식이 올바르지 않습니다(ULID/UUID·최대 128자).");
        }
        return idempotencyKeyHeader;
    }
}
