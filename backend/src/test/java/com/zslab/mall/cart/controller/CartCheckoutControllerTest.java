package com.zslab.mall.cart.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.cart.exception.EmptyCartCheckoutException;
import com.zslab.mall.cart.service.CartCheckoutService;
import com.zslab.mall.checkout.service.CheckoutOutcome;
import com.zslab.mall.common.auth.BuyerActorResolver;
import com.zslab.mall.common.exception.UnauthenticatedException;
import com.zslab.mall.order.controller.response.CheckoutResponse;
import com.zslab.mall.order.controller.response.CheckoutResponse.PaymentView;
import com.zslab.mall.order.controller.response.StatusView;
import com.zslab.mall.payment.enums.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link CartCheckoutController} @WebMvcTest(Track 41 β). HTTP 상태·응답 변환(CheckoutOutcomeSupport)·ProblemDetail 검증.
 * CartCheckoutService·BuyerActorResolver는 mock이며 슬라이스는 컨트롤러 ↔ 전역 예외 핸들러 ↔ HTTP 변환 경계만 검증한다
 * (BuyerOrderControllerTest 대칭·addFilters=false). 비-BUYER 403은 SecurityConfig 필터 계층이라 슬라이스 범위 밖이다.
 */
@WebMvcTest(CartCheckoutController.class)
@AutoConfigureMockMvc(addFilters = false)
class CartCheckoutControllerTest {

    private static final String URL = "/api/v1/cart/checkout";
    private static final String VALID_BODY = """
            {
              "shippingAddress": {
                "recipientName": "홍길동", "recipientPhone": "010-1234-5678",
                "zonecode": "06236", "addressRoad": "서울 강남대로 1"
              },
              "method": "CARD"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartCheckoutService cartCheckoutService;
    @MockitoBean
    private BuyerActorResolver buyerActorResolver;

    @BeforeEach
    void stubBuyerActor() {
        when(buyerActorResolver.resolve(any())).thenReturn(1L);
    }

    private CheckoutResponse paymentResponse(String paymentPublicId) {
        return new CheckoutResponse(
                new PaymentView(paymentPublicId, StatusView.of(PaymentStatus.PENDING), "https://pg/redirect", null), null);
    }

    @Test
    @DisplayName("POST: 장바구니 결제 성공 → 201 + Location + payment 본문")
    void checkout_returns201_withLocation() throws Exception {
        when(cartCheckoutService.checkout(anyLong(), any(), any())).thenReturn(
                CheckoutOutcome.created(paymentResponse("pay_CART00000000000000000000AA"), "/api/v1/orders/ord_C00000000000000000000000AA"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/orders/ord_C00000000000000000000000AA"))
                .andExpect(jsonPath("$.payment.publicId").value("pay_CART00000000000000000000AA"))
                .andExpect(jsonPath("$.payment.status.code").value("PENDING"));
    }

    @Test
    @DisplayName("POST: 멱등성 캐시 재반환 → 200 (Location 없음)")
    void checkout_idempotentCache_returns200() throws Exception {
        when(cartCheckoutService.checkout(anyLong(), any(), any())).thenReturn(
                CheckoutOutcome.cached(paymentResponse("pay_CACHE000000000000000000AA")));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    @DisplayName("POST: selected 0개 → 422 CART_CHECKOUT_EMPTY")
    void checkout_emptyCart_returns422() throws Exception {
        when(cartCheckoutService.checkout(anyLong(), any(), any()))
                .thenThrow(new EmptyCartCheckoutException("장바구니에 선택된 품목이 없습니다."));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CART_CHECKOUT_EMPTY"));
    }

    @Test
    @DisplayName("POST: 미인증(resolver 401) → 401 UNAUTHENTICATED")
    void checkout_unauthenticated_returns401() throws Exception {
        when(buyerActorResolver.resolve(any())).thenThrow(new UnauthenticatedException("인증된 액터가 없습니다"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("POST: 본문 검증 실패(method 누락) → 400 VALIDATION_FAILED")
    void checkout_missingMethod_returns400() throws Exception {
        String body = """
                { "shippingAddress": { "recipientName": "a", "recipientPhone": "b", "zonecode": "c", "addressRoad": "d" } }
                """;
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST: Idempotency-Key 형식 오류 → 400 MALFORMED_REQUEST")
    void checkout_malformedIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post(URL).header("Idempotency-Key", "bad key with spaces!")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
