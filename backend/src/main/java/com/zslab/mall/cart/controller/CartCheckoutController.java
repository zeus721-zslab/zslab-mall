package com.zslab.mall.cart.controller;

import com.zslab.mall.cart.controller.request.CartCheckoutRequest;
import com.zslab.mall.cart.service.CartCheckoutService;
import com.zslab.mall.checkout.controller.CheckoutOutcomeSupport;
import com.zslab.mall.checkout.service.CheckoutOutcome;
import com.zslab.mall.common.auth.BuyerActorResolver;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.order.controller.response.CheckoutResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장바구니 결제 REST 컨트롤러(Track 41 β·buyer 주도). POST /api/v1/cart/checkout 1개 엔드포인트를 노출한다. 인가는
 * SecurityConfig {@code /api/v1/cart/**}→{@code hasRole("BUYER")}가 강제하며(Track 40 매처 재사용·컨트롤러 애노테이션 불요),
 * buyerId는 {@link BuyerActorResolver}가 SecurityContext에서 해소한다(BuyerOrderController 대칭).
 *
 * <p>HTTP 책임만 가진다(thin·D-43.11): 액터 해소·요청 검증(@Valid)·Service 위임·HTTP 변환만 하며, selected 조회·
 * CartCheckoutCommand 조립은 {@link CartCheckoutService}가 담당한다. 응답 변환은 직접주문과 {@link CheckoutOutcomeSupport}를
 * 공유한다(D4).
 */
@RestController
public class CartCheckoutController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** Idempotency-Key 형식 검증(ULID/UUID 허용 문자·최대 128자·BuyerOrderController 정합). */
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[0-9A-Za-z-]{1,128}$");

    private final CartCheckoutService cartCheckoutService;
    private final BuyerActorResolver buyerActorResolver;

    public CartCheckoutController(
            CartCheckoutService cartCheckoutService, BuyerActorResolver buyerActorResolver) {
        this.cartCheckoutService = cartCheckoutService;
        this.buyerActorResolver = buyerActorResolver;
    }

    /** 장바구니 selected 품목 결제(신규 주문 + 첫 결제 시작). 신규 201(+Location)·멱등성 캐시 200·selected 0개 422. */
    @PostMapping("/api/v1/cart/checkout")
    public ResponseEntity<CheckoutResponse> checkout(
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKeyHeader,
            @RequestBody @Valid CartCheckoutRequest request, HttpServletRequest httpRequest) {
        Long buyerId = buyerActorResolver.resolve(httpRequest);
        String idempotencyKey = resolveIdempotencyKey(idempotencyKeyHeader);
        CheckoutOutcome outcome = cartCheckoutService.checkout(buyerId, idempotencyKey, request);
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
