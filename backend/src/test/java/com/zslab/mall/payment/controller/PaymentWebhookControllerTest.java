package com.zslab.mall.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.payment.command.PaymentCallbackCommand;
import com.zslab.mall.payment.enums.CallbackType;
import com.zslab.mall.payment.exception.InvalidCallbackException;
import com.zslab.mall.payment.service.PaymentService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.Mockito;

/**
 * {@link PaymentWebhookController} @WebMvcTest. HTTP 응답 코드(200/422/400)·DTO → Command 변환(D-27·D-34) 검증.
 */
@WebMvcTest(PaymentWebhookController.class)
@AutoConfigureMockMvc(addFilters = false) // Track 31 Phase 1: starter-security 슬라이스 기본잠금 회피(무회귀·200/422/400은 컨트롤러/GEH 생성물이라 무영향)
class PaymentWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    private static final String VALID_BODY = """
            {
              "provider": "MOCK_PG",
              "callbackType": "SUCCESS",
              "paymentAttemptKey": "pat_HTTP000000000000000000000AA",
              "pgTid": "tid_http",
              "occurredAt": "2026-06-26T10:00:00",
              "metadata": { "failureCode": "NONE" }
            }
            """;

    @Test
    @DisplayName("정상 콜백 → 200·DTO가 Command로 변환되어 Service에 전달")
    void validCallback_returns200_andMapsCommand() throws Exception {
        mockMvc.perform(post("/api/webhooks/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<PaymentCallbackCommand> captor = ArgumentCaptor.forClass(PaymentCallbackCommand.class);
        Mockito.verify(paymentService).handleCallback(captor.capture());
        PaymentCallbackCommand command = captor.getValue();
        assertThat(command.provider()).isEqualTo("MOCK_PG");
        assertThat(command.callbackType()).isEqualTo(CallbackType.SUCCESS);
        assertThat(command.paymentAttemptKey()).isEqualTo("pat_HTTP000000000000000000000AA");
        assertThat(command.pgTid()).isEqualTo("tid_http");
        assertThat(command.occurredAt()).isEqualTo(LocalDateTime.of(2026, 6, 26, 10, 0));
        assertThat(command.metadata()).containsEntry("failureCode", "NONE");
    }

    @Test
    @DisplayName("Service가 InvalidCallbackException → HTTP 422(REJECT)")
    void invalidCallback_returns422() throws Exception {
        doThrow(new InvalidCallbackException("종결 상태 불법 전이"))
                .when(paymentService).handleCallback(Mockito.any());

        mockMvc.perform(post("/api/webhooks/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("필수 필드 누락(provider 공백) → HTTP 400(Bean Validation)")
    void missingProvider_returns400() throws Exception {
        String invalidBody = """
                {
                  "provider": "",
                  "callbackType": "SUCCESS",
                  "paymentAttemptKey": "pat_HTTP000000000000000000000AA",
                  "occurredAt": "2026-06-26T10:00:00"
                }
                """;

        mockMvc.perform(post("/api/webhooks/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }
}
