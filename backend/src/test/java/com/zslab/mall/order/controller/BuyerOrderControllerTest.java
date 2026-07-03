package com.zslab.mall.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.checkout.service.CheckoutOutcome;
import com.zslab.mall.checkout.service.CheckoutService;
import com.zslab.mall.order.controller.response.CheckoutResponse;
import com.zslab.mall.order.controller.response.CheckoutResponse.PaymentView;
import com.zslab.mall.order.controller.response.OrderResponse;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.controller.response.StatusView;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.exception.OrderNotPayableException;
import com.zslab.mall.order.exception.OrderNotPayableReason;
import com.zslab.mall.order.service.BuyerOrderQueryService;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.exception.PaymentInProgressException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link BuyerOrderController} @WebMvcTest. HTTP 상태 매트릭스(§17)·인증 헤더 해소(§2)·ProblemDetail(§14) 검증.
 * Service는 mock이며, 본 슬라이스는 컨트롤러 ↔ 전역 예외 핸들러 ↔ HTTP 변환 경계만 검증한다.
 */
@WebMvcTest(BuyerOrderController.class)
@AutoConfigureMockMvc(addFilters = false) // Track 31 Phase 1: starter-security 슬라이스 기본잠금 회피(무회귀·401 등은 컨트롤러/GEH 생성물이라 무영향)
class BuyerOrderControllerTest {

    private static final String BUYER_ID = "1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckoutService checkoutService;
    @MockitoBean
    private BuyerOrderQueryService buyerOrderQueryService;

    private static final String VALID_BODY = """
            {
              "items": [ { "productId": "prd_TEST0000000000000000000AA", "variantId": "var_TEST0000000000000000000AA", "quantity": 2 } ],
              "shippingAddress": {
                "recipientName": "홍길동", "recipientPhone": "010-1234-5678",
                "zonecode": "06236", "addressRoad": "서울 강남대로 1"
              },
              "method": "CARD"
            }
            """;

    private CheckoutResponse paymentResponse(String paymentPublicId) {
        return new CheckoutResponse(
                new PaymentView(paymentPublicId, StatusView.of(PaymentStatus.PENDING), "https://pg/redirect", null), null);
    }

    @Test
    @DisplayName("POST: 신규 주문 성공 → 201 + Location + payment 본문")
    void create_returns201_withLocation() throws Exception {
        when(checkoutService.checkout(any())).thenReturn(
                CheckoutOutcome.created(paymentResponse("pay_NEW00000000000000000000AA"), "/api/v1/orders/ord_X00000000000000000000000AA"));

        mockMvc.perform(post("/api/v1/orders").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/orders/ord_X00000000000000000000000AA"))
                .andExpect(jsonPath("$.payment.publicId").value("pay_NEW00000000000000000000AA"))
                .andExpect(jsonPath("$.payment.status.code").value("PENDING"));
    }

    @Test
    @DisplayName("POST: 멱등성 캐시 재반환 → 200 (Location 없음)")
    void create_idempotentCache_returns200() throws Exception {
        when(checkoutService.checkout(any())).thenReturn(
                CheckoutOutcome.cached(paymentResponse("pay_CACHE000000000000000000AA")));

        mockMvc.perform(post("/api/v1/orders").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    @DisplayName("POST: X-Buyer-Id 누락 → 401 UNAUTHENTICATED")
    void create_missingBuyerId_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("POST: X-Buyer-Id 형식 오류 → 400 MALFORMED_REQUEST")
    void create_malformedBuyerId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/orders").header("X-Buyer-Id", "not-a-number")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("POST: 본문 검증 실패(items 비어 있음) → 400 VALIDATION_FAILED")
    void create_emptyItems_returns400() throws Exception {
        String body = """
                { "items": [], "shippingAddress": { "recipientName": "a", "recipientPhone": "b", "zonecode": "c", "addressRoad": "d" }, "method": "CARD" }
                """;
        mockMvc.perform(post("/api/v1/orders").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST: Idempotency-Key 형식 오류 → 400 MALFORMED_REQUEST")
    void create_malformedIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/orders").header("X-Buyer-Id", BUYER_ID)
                        .header("Idempotency-Key", "bad key with spaces!")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("GET 단건: 타인/미존재 주문 → 404 ORDER_NOT_FOUND")
    void getOne_notFound_returns404() throws Exception {
        when(buyerOrderQueryService.getOrder(anyString(), anyLong()))
                .thenThrow(new OrderNotFoundException("주문을 찾을 수 없습니다"));

        mockMvc.perform(get("/api/v1/orders/ord_X00000000000000000000000AA").header("X-Buyer-Id", BUYER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET 목록: 200 PagedResponse")
    void list_returns200() throws Exception {
        when(buyerOrderQueryService.listOrders(anyLong(), anyInt(), anyInt()))
                .thenReturn(new PagedResponse<>(List.of(), 0, 20, 0L, false));

        mockMvc.perform(get("/api/v1/orders").header("X-Buyer-Id", BUYER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("재결제: 활성 Payment 존재 → 409 PAYMENT_IN_PROGRESS")
    void retry_activePayment_returns409() throws Exception {
        when(checkoutService.retryPayment(anyString(), anyLong(), any()))
                .thenThrow(new PaymentInProgressException("진행 중인 결제가 있습니다"));

        mockMvc.perform(post("/api/v1/orders/ord_X00000000000000000000000AA/payments").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"method\": \"CARD\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_PROGRESS"));
    }

    @Test
    @DisplayName("재결제: 재검증 불가(재고 부족) → 422 ORDER_NOT_PAYABLE + detail")
    void retry_notPayable_returns422() throws Exception {
        when(checkoutService.retryPayment(anyString(), anyLong(), any()))
                .thenThrow(new OrderNotPayableException(OrderNotPayableReason.OUT_OF_STOCK, "재고 부족"));

        mockMvc.perform(post("/api/v1/orders/ord_X00000000000000000000000AA/payments").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"method\": \"CARD\" }"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"))
                .andExpect(jsonPath("$.detail").value("OUT_OF_STOCK"));
    }

    @Test
    @DisplayName("재결제: 성공 → 201 + Location(payment 리소스)")
    void retry_returns201_withPaymentLocation() throws Exception {
        when(checkoutService.retryPayment(anyString(), anyLong(), any())).thenReturn(
                CheckoutOutcome.created(paymentResponse("pay_RETRY00000000000000000AA"), "/api/v1/payments/pay_RETRY00000000000000000AA"));

        mockMvc.perform(post("/api/v1/orders/ord_X00000000000000000000000AA/payments").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"method\": \"CARD\" }"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/payments/pay_RETRY00000000000000000AA"));
    }
}
