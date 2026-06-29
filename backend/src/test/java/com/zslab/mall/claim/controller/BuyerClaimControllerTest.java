package com.zslab.mall.claim.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.controller.response.ClaimResponse;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.order.controller.response.PagedResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link BuyerClaimController} @WebMvcTest(BuyerOrderControllerTest 패턴 1:1). HTTP 상태 매트릭스(201·200·400·401·404·422)·
 * 인증 헤더 해소(D-39)·@Valid 검증·전역 예외 매핑(D-50 422·J1 404·J4 400) 경계만 검증한다. Service는 mock이다.
 */
@WebMvcTest(BuyerClaimController.class)
class BuyerClaimControllerTest {

    private static final String BUYER_ID = "1";
    private static final String ORDER_ITEM_PUBLIC_ID = "oit_01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final String CLAIM_PUBLIC_ID = "clm_01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 6, 29, 9, 0);

    private static final String VALID_BODY = """
            {
              "orderItemPublicId": "oit_01ARZ3NDEKTSV4RRFFQ69G5FAV",
              "claimType": "CANCEL",
              "reasonCode": "BUYER_CHANGED_MIND",
              "reasonDetail": "단순 변심"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClaimService claimService;

    private Claim mockClaim() {
        Claim claim = org.mockito.Mockito.mock(Claim.class);
        when(claim.getPublicId()).thenReturn(CLAIM_PUBLIC_ID);
        when(claim.getType()).thenReturn(ClaimType.CANCEL);
        when(claim.getStatus()).thenReturn(ClaimStatus.REQUESTED);
        when(claim.getReasonCode()).thenReturn("BUYER_CHANGED_MIND");
        when(claim.getReasonDetail()).thenReturn("단순 변심");
        when(claim.getRequestedAt()).thenReturn(REQUESTED_AT);
        when(claim.getProcessedAt()).thenReturn(null);
        return claim;
    }

    private ClaimResponse claimResponse() {
        return new ClaimResponse(CLAIM_PUBLIC_ID, ORDER_ITEM_PUBLIC_ID, ClaimType.CANCEL, ClaimStatus.REQUESTED,
                "BUYER_CHANGED_MIND", "단순 변심", REQUESTED_AT, null);
    }

    // ===== POST /api/v1/claims =====

    @Test
    @DisplayName("POST: 정상 요청 → 201 + Location + ClaimResponse 본문")
    void request_returns201_withLocation() throws Exception {
        // mockClaim() 내부 when() 호출이 바깥 스터빙 진행 중 실행되지 않도록 먼저 생성한다(중첩 스터빙 회피).
        Claim claim = mockClaim();
        when(claimService.request(any())).thenReturn(claim);

        mockMvc.perform(post("/api/v1/claims").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/claims/" + CLAIM_PUBLIC_ID))
                .andExpect(jsonPath("$.publicId").value(CLAIM_PUBLIC_ID))
                .andExpect(jsonPath("$.orderItemPublicId").value(ORDER_ITEM_PUBLIC_ID))
                .andExpect(jsonPath("$.claimType").value("CANCEL"))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.reasonCode").value("BUYER_CHANGED_MIND"));
    }

    @Test
    @DisplayName("POST: X-Buyer-Id 누락 → 401 UNAUTHENTICATED")
    void request_missingBuyerId_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/claims")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("POST: X-Buyer-Id 형식 오류 → 400 MALFORMED_REQUEST")
    void request_malformedBuyerId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/claims").header("X-Buyer-Id", "not-a-number")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("POST: orderItemPublicId @Pattern 위반 → 400 VALIDATION_FAILED")
    void request_malformedOrderItemPublicId_returns400() throws Exception {
        String body = """
                { "orderItemPublicId": "invalid", "claimType": "CANCEL", "reasonCode": "BUYER_CHANGED_MIND" }
                """;
        mockMvc.perform(post("/api/v1/claims").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST: orderItemPublicId 공백 → 400 VALIDATION_FAILED")
    void request_blankOrderItemPublicId_returns400() throws Exception {
        String body = """
                { "orderItemPublicId": "", "claimType": "CANCEL", "reasonCode": "BUYER_CHANGED_MIND" }
                """;
        mockMvc.perform(post("/api/v1/claims").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST: claimType 누락(@NotNull) → 400 VALIDATION_FAILED")
    void request_nullClaimType_returns400() throws Exception {
        String body = """
                { "orderItemPublicId": "oit_01ARZ3NDEKTSV4RRFFQ69G5FAV", "reasonCode": "BUYER_CHANGED_MIND" }
                """;
        mockMvc.perform(post("/api/v1/claims").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST: reasonCode 누락(@NotNull) → 400 VALIDATION_FAILED")
    void request_nullReasonCode_returns400() throws Exception {
        String body = """
                { "orderItemPublicId": "oit_01ARZ3NDEKTSV4RRFFQ69G5FAV", "claimType": "CANCEL" }
                """;
        mockMvc.perform(post("/api/v1/claims").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST: reasonDetail 500자 초과(@Size) → 400 VALIDATION_FAILED")
    void request_tooLongReasonDetail_returns400() throws Exception {
        String body = "{ \"orderItemPublicId\": \"" + ORDER_ITEM_PUBLIC_ID
                + "\", \"claimType\": \"CANCEL\", \"reasonCode\": \"BUYER_CHANGED_MIND\", \"reasonDetail\": \""
                + "a".repeat(501) + "\" }";
        mockMvc.perform(post("/api/v1/claims").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST: reasonCode 잘못된 enum 값 → 400 MALFORMED_REQUEST(역직렬화 실패·J4)")
    void request_invalidEnumValue_returns400() throws Exception {
        String body = """
                { "orderItemPublicId": "oit_01ARZ3NDEKTSV4RRFFQ69G5FAV", "claimType": "CANCEL", "reasonCode": "INVALID_CODE" }
                """;
        mockMvc.perform(post("/api/v1/claims").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("POST: Service ClaimNotFoundException → 404 CLAIM_NOT_FOUND")
    void request_serviceNotFound_returns404() throws Exception {
        when(claimService.request(any())).thenThrow(new ClaimNotFoundException("주문 품목을 찾을 수 없습니다"));

        mockMvc.perform(post("/api/v1/claims").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST: Service ClaimInvalidStateException → 422 CLAIM_STATE_INVALID")
    void request_serviceInvalidState_returns422() throws Exception {
        when(claimService.request(any())).thenThrow(new ClaimInvalidStateException("취소 요청 불가 상태"));

        mockMvc.perform(post("/api/v1/claims").header("X-Buyer-Id", BUYER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));
    }

    // ===== GET /api/v1/claims/{claimPublicId} =====

    @Test
    @DisplayName("GET 단건: 정상 조회 → 200 + ClaimResponse")
    void getOne_returns200() throws Exception {
        when(claimService.getClaim(eq(CLAIM_PUBLIC_ID), anyLong())).thenReturn(claimResponse());

        mockMvc.perform(get("/api/v1/claims/" + CLAIM_PUBLIC_ID).header("X-Buyer-Id", BUYER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(CLAIM_PUBLIC_ID))
                .andExpect(jsonPath("$.orderItemPublicId").value(ORDER_ITEM_PUBLIC_ID))
                .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    @DisplayName("GET 단건: X-Buyer-Id 누락 → 401 UNAUTHENTICATED")
    void getOne_missingBuyerId_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/claims/" + CLAIM_PUBLIC_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("GET 단건: 미존재·타인 → 404 CLAIM_NOT_FOUND(정보 노출 회피·Q8)")
    void getOne_notFound_returns404() throws Exception {
        when(claimService.getClaim(anyString(), anyLong()))
                .thenThrow(new ClaimNotFoundException("클레임을 찾을 수 없습니다"));

        mockMvc.perform(get("/api/v1/claims/" + CLAIM_PUBLIC_ID).header("X-Buyer-Id", BUYER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET 단건: 형식 불일치 publicId → path @Pattern 미적용·Service 도달 후 404(J1)")
    void getOne_malformedPublicId_reachesServiceAnd404() throws Exception {
        when(claimService.getClaim(eq("not-a-valid-id"), anyLong()))
                .thenThrow(new ClaimNotFoundException("클레임을 찾을 수 없습니다"));

        mockMvc.perform(get("/api/v1/claims/not-a-valid-id").header("X-Buyer-Id", BUYER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        verify(claimService).getClaim(eq("not-a-valid-id"), anyLong());
    }

    // ===== GET /api/v1/claims =====

    @Test
    @DisplayName("GET 목록: 정상 → 200 PagedResponse 구조")
    void list_returns200() throws Exception {
        when(claimService.listClaims(anyLong(), anyInt(), anyInt()))
                .thenReturn(new PagedResponse<>(List.of(), 0, 20, 0L, false));

        mockMvc.perform(get("/api/v1/claims").header("X-Buyer-Id", BUYER_ID)
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    @DisplayName("GET 목록: X-Buyer-Id 누락 → 401 UNAUTHENTICATED")
    void list_missingBuyerId_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/claims"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("GET 목록: size 파라미터 누락 → defaultValue 20 적용")
    void list_defaultSize_returns200() throws Exception {
        when(claimService.listClaims(anyLong(), anyInt(), anyInt()))
                .thenReturn(new PagedResponse<>(List.of(), 5, 20, 0L, false));

        mockMvc.perform(get("/api/v1/claims").header("X-Buyer-Id", BUYER_ID).param("page", "5"))
                .andExpect(status().isOk());
        verify(claimService).listClaims(eq(1L), eq(5), eq(20));
    }

    @Test
    @DisplayName("GET 목록: page 파라미터 누락 → defaultValue 0 적용")
    void list_defaultPage_returns200() throws Exception {
        when(claimService.listClaims(anyLong(), anyInt(), anyInt()))
                .thenReturn(new PagedResponse<>(List.of(), 0, 50, 0L, false));

        mockMvc.perform(get("/api/v1/claims").header("X-Buyer-Id", BUYER_ID).param("size", "50"))
                .andExpect(status().isOk());
        verify(claimService).listClaims(eq(1L), eq(0), eq(50));
    }
}
