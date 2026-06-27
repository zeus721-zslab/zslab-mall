package com.zslab.mall.order.controller;

import com.zslab.mall.checkout.service.CheckoutOutcome;
import com.zslab.mall.checkout.service.CheckoutService;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.exception.UnauthenticatedException;
import com.zslab.mall.order.controller.request.CreateOrderRequest;
import com.zslab.mall.order.controller.request.RetryPaymentRequest;
import com.zslab.mall.order.controller.response.CheckoutResponse;
import com.zslab.mall.order.controller.response.OrderResponse;
import com.zslab.mall.order.controller.response.OrderSummaryResponse;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.service.BuyerOrderQueryService;
import jakarta.validation.Valid;
import java.net.URI;
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
 * <p>HTTP 책임만 가진다(D-43.11): 인증 헤더 해소·Service 위임·HTTP 변환만 수행하며 Repository 직접 접근·트랜잭션 제어·
 * 결제 규칙 판단을 하지 않는다. 조립은 {@link CheckoutService}(쓰기), 조회 enrich는 {@link BuyerOrderQueryService}가 담당한다.
 *
 * <p>임시 인증(D-39): {@code X-Buyer-Id} 헤더(BIGINT). 누락 401·형식 오류 400·대상 불일치 404(§2).
 */
@RestController
@RequestMapping("/api/v1/orders")
public class BuyerOrderController {

    private static final String BUYER_ID_HEADER = "X-Buyer-Id";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** Idempotency-Key 형식 검증(ULID/UUID 허용 문자·최대 128자·§8). */
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[0-9A-Za-z-]{1,128}$");

    private final CheckoutService checkoutService;
    private final BuyerOrderQueryService buyerOrderQueryService;

    public BuyerOrderController(CheckoutService checkoutService, BuyerOrderQueryService buyerOrderQueryService) {
        this.checkoutService = checkoutService;
        this.buyerOrderQueryService = buyerOrderQueryService;
    }

    /** 주문 생성 + 첫 결제 시작(§5). 신규/재초기화 201(+Location)·멱등성 캐시 200. */
    @PostMapping
    public ResponseEntity<CheckoutResponse> create(
            @RequestHeader(value = BUYER_ID_HEADER, required = false) String buyerIdHeader,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKeyHeader,
            @RequestBody @Valid CreateOrderRequest request) {
        Long buyerId = resolveBuyerId(buyerIdHeader);
        String idempotencyKey = resolveIdempotencyKey(idempotencyKeyHeader);
        CheckoutOutcome outcome = checkoutService.checkout(request.toCommand(buyerId, idempotencyKey));
        return toResponseEntity(outcome);
    }

    /** 본인 주문 단건 조회(§11 seller 그룹화). */
    @GetMapping("/{orderPublicId}")
    public ResponseEntity<OrderResponse> getOne(
            @RequestHeader(value = BUYER_ID_HEADER, required = false) String buyerIdHeader,
            @PathVariable String orderPublicId) {
        Long buyerId = resolveBuyerId(buyerIdHeader);
        return ResponseEntity.ok(buyerOrderQueryService.getOrder(orderPublicId, buyerId));
    }

    /** 본인 주문 목록(D-54 PagedResponse·ordered_at DESC·sort 미노출). */
    @GetMapping
    public ResponseEntity<PagedResponse<OrderSummaryResponse>> list(
            @RequestHeader(value = BUYER_ID_HEADER, required = false) String buyerIdHeader,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long buyerId = resolveBuyerId(buyerIdHeader);
        return ResponseEntity.ok(buyerOrderQueryService.listOrders(buyerId, page, size));
    }

    /** 재결제(§6). 재검증 2종(D-60) 후 신규 Payment 생성·201(Location=payment). */
    @PostMapping("/{orderPublicId}/payments")
    public ResponseEntity<CheckoutResponse> retryPayment(
            @RequestHeader(value = BUYER_ID_HEADER, required = false) String buyerIdHeader,
            @PathVariable String orderPublicId,
            @RequestBody @Valid RetryPaymentRequest request) {
        Long buyerId = resolveBuyerId(buyerIdHeader);
        CheckoutOutcome outcome = checkoutService.retryPayment(orderPublicId, buyerId, request.method());
        return toResponseEntity(outcome);
    }

    /** 멱등성 캐시 재반환은 200(Location 없음·§10), 그 외 신규/재결제는 201 + Location(D-53). */
    private ResponseEntity<CheckoutResponse> toResponseEntity(CheckoutOutcome outcome) {
        if (outcome.cached()) {
            return ResponseEntity.ok(outcome.response());
        }
        return ResponseEntity.created(URI.create(outcome.location())).body(outcome.response());
    }

    /** X-Buyer-Id 헤더를 BIGINT로 해소한다. 누락 → 401, 파싱 실패 → 400(§2). */
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
