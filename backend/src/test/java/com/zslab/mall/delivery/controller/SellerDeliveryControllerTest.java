package com.zslab.mall.delivery.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link SellerDeliveryController} @WebMvcTest(SellerClaimControllerTest 패턴 1:1·D-99). HTTP 상태 매트릭스(200·400·404)·
 * publicId 해소(D-99 Q5)·전역 예외 매핑(D-99 Q10)만 검증한다. Service·Repository·Resolver는 mock이다.
 *
 * <p>권한 위반(cross-tenant)은 ClaimService가 {@link ClaimNotFoundException}을 던지는 흐름만 stub해 컨트롤러의 예외 전파를 본다.
 * 실제 권한 검증(authorizeSellerAccess)·이중 호출 멱등 가드(Q11)는 Service 단위·통합 테스트(세션 2) 책임이다.
 */
@WebMvcTest(SellerDeliveryController.class)
class SellerDeliveryControllerTest {

    private static final String CLAIM_PUBLIC_ID = "clm_01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final String DELIVERY_PUBLIC_ID = "dlv_01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final long CLAIM_ID = 10L;
    private static final long SELLER_ID = 1L;
    private static final long OTHER_SELLER_ID = 2L;
    private static final String TRACKING_NO = "1234567890";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClaimService claimService;

    @MockitoBean
    private ClaimRepository claimRepository;

    @MockitoBean
    private SellerActorResolver sellerActorResolver;

    private Claim mockClaim() {
        Claim claim = org.mockito.Mockito.mock(Claim.class);
        when(claim.getId()).thenReturn(CLAIM_ID);
        return claim;
    }

    private Delivery mockDelivery() {
        Delivery delivery = org.mockito.Mockito.mock(Delivery.class);
        when(delivery.getPublicId()).thenReturn(DELIVERY_PUBLIC_ID);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.SHIPPING);
        when(delivery.getCarrier()).thenReturn(DeliveryCarrier.CJ);
        when(delivery.getTrackingNo()).thenReturn(TRACKING_NO);
        return delivery;
    }

    private String body(String carrier, String trackingNo) {
        return "{\"carrier\":\"" + carrier + "\",\"trackingNo\":\"" + trackingNo + "\"}";
    }

    // ===== POST /api/v1/claims/{claimPublicId}/register-exchange-shipment =====

    @Test
    @DisplayName("T1 정상 등록 → 200 + RegisterExchangeShipmentResponse·registerExchangeShipmentBySeller 위임")
    void register_returns200() throws Exception {
        // 중첩 스터빙 회피: 협력 mock을 바깥 스터빙 전에 완성한다(SellerClaimControllerTest 패턴).
        Claim claim = mockClaim();
        Delivery delivery = mockDelivery();
        when(sellerActorResolver.resolve(any())).thenReturn(SELLER_ID);
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.of(claim));
        when(claimService.registerExchangeShipmentBySeller(
                eq(CLAIM_ID), eq(SELLER_ID), eq(DeliveryCarrier.CJ), eq(TRACKING_NO))).thenReturn(delivery);

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PUBLIC_ID + "/register-exchange-shipment")
                        .header("X-Seller-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", TRACKING_NO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryPublicId").value(DELIVERY_PUBLIC_ID))
                .andExpect(jsonPath("$.status").value("SHIPPING"))
                .andExpect(jsonPath("$.carrier").value("CJ"))
                .andExpect(jsonPath("$.trackingNo").value(TRACKING_NO));
        verify(claimService).registerExchangeShipmentBySeller(
                eq(CLAIM_ID), eq(SELLER_ID), eq(DeliveryCarrier.CJ), eq(TRACKING_NO));
    }

    @Test
    @DisplayName("T2 미존재 claimPublicId → 404 CLAIM_NOT_FOUND·Service 미호출")
    void register_unknownPublicId_returns404() throws Exception {
        when(sellerActorResolver.resolve(any())).thenReturn(SELLER_ID);
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PUBLIC_ID + "/register-exchange-shipment")
                        .header("X-Seller-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", TRACKING_NO)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        verify(claimService, never()).registerExchangeShipmentBySeller(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("T3 타 Seller cross-tenant → 404 CLAIM_NOT_FOUND(정보 노출 회피·D-99 Q9·Q10)")
    void register_crossTenant_returns404() throws Exception {
        Claim claim = mockClaim();
        when(sellerActorResolver.resolve(any())).thenReturn(OTHER_SELLER_ID);
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.of(claim));
        when(claimService.registerExchangeShipmentBySeller(
                eq(CLAIM_ID), eq(OTHER_SELLER_ID), eq(DeliveryCarrier.CJ), eq(TRACKING_NO)))
                .thenThrow(new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + CLAIM_ID));

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PUBLIC_ID + "/register-exchange-shipment")
                        .header("X-Seller-Id", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", TRACKING_NO)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    @Test
    @DisplayName("T4 trackingNo 공백 → 400 VALIDATION_FAILED(@NotBlank·Q3·Q4 β)·Service 미호출")
    void register_blankTrackingNo_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PUBLIC_ID + "/register-exchange-shipment")
                        .header("X-Seller-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(claimService, never()).registerExchangeShipmentBySeller(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("T5 carrier 잘못된 값 → 400 MALFORMED_REQUEST(Jackson enum 역직렬화 실패·Q4 β)·Service 미호출")
    void register_invalidCarrier_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PUBLIC_ID + "/register-exchange-shipment")
                        .header("X-Seller-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("INVALID_CARRIER", TRACKING_NO)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verify(claimService, never()).registerExchangeShipmentBySeller(anyLong(), anyLong(), any(), any());
    }
}
