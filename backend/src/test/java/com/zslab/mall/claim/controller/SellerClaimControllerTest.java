package com.zslab.mall.claim.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.exception.UnauthenticatedException;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link SellerClaimController} @WebMvcTest(BuyerClaimControllerTest 패턴 1:1·D-92). HTTP 상태 매트릭스(200·400·401·404·422)·
 * 액터 해소(D-39)·전역 예외 매핑·실패 우선순위(권한 → 상태)만 검증한다. Service·Repository·Resolver는 mock이다.
 *
 * <p>실제 헤더 파싱(X-Seller-Id → BIGINT)은 {@link com.zslab.mall.common.auth.HeaderSellerActorResolver} 책임이며
 * SellerClaimIntegrationTest가 실 헤더로 검증한다. 본 테스트는 resolver를 stub해 컨트롤러의 예외 전파만 본다.
 */
@WebMvcTest(SellerClaimController.class)
class SellerClaimControllerTest {

    private static final String CLAIM_PUBLIC_ID = "clm_01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final String ORDER_ITEM_PUBLIC_ID = "oit_01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final long CLAIM_ID = 10L;
    private static final long ORDER_ITEM_ID = 20L;
    private static final long SELLER_ID = 1L;
    private static final long OTHER_SELLER_ID = 2L;
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 6, 30, 9, 0);
    private static final LocalDateTime PROCESSED_AT = LocalDateTime.of(2026, 6, 30, 10, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClaimService claimService;

    @MockitoBean
    private ClaimRepository claimRepository;

    @MockitoBean
    private OrderItemRepository orderItemRepository;

    @MockitoBean
    private SellerActorResolver sellerActorResolver;

    private Claim mockClaim(ClaimStatus status) {
        Claim claim = org.mockito.Mockito.mock(Claim.class);
        when(claim.getId()).thenReturn(CLAIM_ID);
        when(claim.getOrderItemId()).thenReturn(ORDER_ITEM_ID);
        when(claim.getPublicId()).thenReturn(CLAIM_PUBLIC_ID);
        when(claim.getType()).thenReturn(ClaimType.CANCEL);
        when(claim.getStatus()).thenReturn(status);
        when(claim.getReasonCode()).thenReturn("BUYER_CHANGED_MIND");
        when(claim.getReasonDetail()).thenReturn("단순 변심");
        when(claim.getRequestedAt()).thenReturn(REQUESTED_AT);
        when(claim.getProcessedAt()).thenReturn(PROCESSED_AT);
        return claim;
    }

    private OrderItem mockOrderItem() {
        OrderItem orderItem = org.mockito.Mockito.mock(OrderItem.class);
        when(orderItem.getPublicId()).thenReturn(ORDER_ITEM_PUBLIC_ID);
        return orderItem;
    }

    // ===== POST /api/v1/claims/{claimPublicId}/approve =====

    @Test
    @DisplayName("T1 승인: 정상 → 200 + 갱신된 ClaimResponse(APPROVED)·approveBySeller 위임")
    void approve_returns200() throws Exception {
        // 중첩 스터빙 회피: 협력 mock을 바깥 스터빙 전에 완성한다(BuyerClaimControllerTest 패턴).
        Claim claim = mockClaim(ClaimStatus.APPROVED);
        OrderItem orderItem = mockOrderItem();
        when(sellerActorResolver.resolve(any())).thenReturn(SELLER_ID);
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.of(claim));
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PUBLIC_ID + "/approve").header("X-Seller-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(CLAIM_PUBLIC_ID))
                .andExpect(jsonPath("$.orderItemPublicId").value(ORDER_ITEM_PUBLIC_ID))
                .andExpect(jsonPath("$.claimType").value("CANCEL"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        verify(claimService).approveBySeller(eq(CLAIM_ID), eq(SELLER_ID), any());
    }

    @Test
    @DisplayName("T2 승인: X-Seller-Id 누락 → 401 UNAUTHENTICATED·approveBySeller 미호출")
    void approve_missingSellerId_returns401() throws Exception {
        when(sellerActorResolver.resolve(any())).thenThrow(new UnauthenticatedException("X-Seller-Id 헤더 누락"));

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PUBLIC_ID + "/approve"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verify(claimService, never()).approveBySeller(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("T3 승인: X-Seller-Id 형식 오류 → 400 MALFORMED_REQUEST")
    void approve_malformedSellerId_returns400() throws Exception {
        when(sellerActorResolver.resolve(any())).thenThrow(new MalformedRequestException("X-Seller-Id 형식 오류"));

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PUBLIC_ID + "/approve").header("X-Seller-Id", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("T4 승인: 타 Seller cross-tenant → 404 CLAIM_NOT_FOUND(정보 노출 회피·D-92 Q3)")
    void approve_crossTenant_returns404() throws Exception {
        Claim claim = mockClaim(ClaimStatus.REQUESTED);
        when(sellerActorResolver.resolve(any())).thenReturn(OTHER_SELLER_ID);
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.of(claim));
        doThrow(new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + CLAIM_ID))
                .when(claimService).approveBySeller(eq(CLAIM_ID), eq(OTHER_SELLER_ID), any());

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PUBLIC_ID + "/approve").header("X-Seller-Id", "2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    @Test
    @DisplayName("T5 승인: 미존재 claimPublicId → 404 CLAIM_NOT_FOUND·approveBySeller 미호출")
    void approve_unknownPublicId_returns404() throws Exception {
        when(sellerActorResolver.resolve(any())).thenReturn(SELLER_ID);
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PUBLIC_ID + "/approve").header("X-Seller-Id", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        verify(claimService, never()).approveBySeller(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("T6 승인: REQUESTED 아님 → 422 CLAIM_STATE_INVALID(CLM-4)")
    void approve_invalidState_returns422() throws Exception {
        Claim claim = mockClaim(ClaimStatus.APPROVED);
        when(sellerActorResolver.resolve(any())).thenReturn(SELLER_ID);
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.of(claim));
        doThrow(new ClaimInvalidStateException("불법 클레임 상태 전이"))
                .when(claimService).approveBySeller(eq(CLAIM_ID), eq(SELLER_ID), any());

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PUBLIC_ID + "/approve").header("X-Seller-Id", "1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));
    }

    // ===== POST /api/v1/claims/{claimPublicId}/reject =====

    @Test
    @DisplayName("T7 거부: 정상 → 200 + 갱신된 ClaimResponse(REJECTED)·rejectBySeller 위임")
    void reject_returns200() throws Exception {
        Claim claim = mockClaim(ClaimStatus.REJECTED);
        OrderItem orderItem = mockOrderItem();
        when(sellerActorResolver.resolve(any())).thenReturn(SELLER_ID);
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.of(claim));
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PUBLIC_ID + "/reject").header("X-Seller-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(CLAIM_PUBLIC_ID))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        verify(claimService).rejectBySeller(eq(CLAIM_ID), eq(SELLER_ID), any());
    }

    @Test
    @DisplayName("T8 거부: 권한·상태 동시 위반 → 권한 우선 404(422 아님·D-92 실패 우선순위)")
    void reject_authBeforeState_returns404() throws Exception {
        Claim claim = mockClaim(ClaimStatus.APPROVED); // 상태도 위반이나 권한 검증이 선행되어 404가 우선한다
        when(sellerActorResolver.resolve(any())).thenReturn(OTHER_SELLER_ID);
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.of(claim));
        doThrow(new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + CLAIM_ID))
                .when(claimService).rejectBySeller(eq(CLAIM_ID), eq(OTHER_SELLER_ID), any());

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PUBLIC_ID + "/reject").header("X-Seller-Id", "2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }
}
