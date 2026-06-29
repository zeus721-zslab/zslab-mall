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
import com.zslab.mall.common.auth.AdminActorResolver;
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
 * {@link AdminClaimController} @WebMvcTest(SellerClaimControllerTest 패턴 1:1·D-93). HTTP 상태 매트릭스
 * (200·400·401·404·422)·액터 해소(X-Admin-Id·D-93 Q1)·전역 예외 매핑만 검증한다. Service·Repository·Resolver는 mock이다.
 *
 * <p>Admin은 전체 접근이므로 cross-tenant(404 우선) 시나리오가 부재해 Seller 테스트의 T4(cross-tenant)·T8(권한+상태
 * 동시 위반)은 제외한다(D-93 Q3·Q5·전체 접근·권한 개념 없음). 실 헤더 파싱은 {@link
 * com.zslab.mall.common.auth.HeaderAdminActorResolver} 책임이며 AdminClaimIntegrationTest가 실 헤더로 검증한다.
 */
@WebMvcTest(AdminClaimController.class)
class AdminClaimControllerTest {

    private static final String CLAIM_PUBLIC_ID = "clm_01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final String ORDER_ITEM_PUBLIC_ID = "oit_01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final long CLAIM_ID = 10L;
    private static final long ORDER_ITEM_ID = 20L;
    private static final long ADMIN_ID = 1L;
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
    private AdminActorResolver adminActorResolver;

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

    // ===== POST /api/v1/admin/claims/{claimPublicId}/approve =====

    @Test
    @DisplayName("T1 승인: 정상 → 200 + 갱신된 ClaimResponse(APPROVED)·approveByAdmin 위임")
    void approve_returns200() throws Exception {
        // 중첩 스터빙 회피: 협력 mock을 바깥 스터빙 전에 완성한다(SellerClaimControllerTest 패턴).
        Claim claim = mockClaim(ClaimStatus.APPROVED);
        OrderItem orderItem = mockOrderItem();
        when(adminActorResolver.resolve(any())).thenReturn(ADMIN_ID);
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.of(claim));
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));

        mockMvc.perform(post("/api/v1/admin/claims/" + CLAIM_PUBLIC_ID + "/approve").header("X-Admin-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(CLAIM_PUBLIC_ID))
                .andExpect(jsonPath("$.orderItemPublicId").value(ORDER_ITEM_PUBLIC_ID))
                .andExpect(jsonPath("$.claimType").value("CANCEL"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        verify(claimService).approveByAdmin(eq(CLAIM_ID), any());
    }

    @Test
    @DisplayName("T2 승인: X-Admin-Id 누락 → 401 UNAUTHENTICATED·approveByAdmin 미호출")
    void approve_missingAdminId_returns401() throws Exception {
        when(adminActorResolver.resolve(any())).thenThrow(new UnauthenticatedException("X-Admin-Id 헤더 누락"));

        mockMvc.perform(post("/api/v1/admin/claims/" + CLAIM_PUBLIC_ID + "/approve"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verify(claimService, never()).approveByAdmin(anyLong(), any());
    }

    @Test
    @DisplayName("T3 승인: X-Admin-Id 형식 오류 → 400 MALFORMED_REQUEST")
    void approve_malformedAdminId_returns400() throws Exception {
        when(adminActorResolver.resolve(any())).thenThrow(new MalformedRequestException("X-Admin-Id 형식 오류"));

        mockMvc.perform(post("/api/v1/admin/claims/" + CLAIM_PUBLIC_ID + "/approve").header("X-Admin-Id", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("T4 승인: 미존재 claimPublicId → 404 CLAIM_NOT_FOUND·approveByAdmin 미호출")
    void approve_unknownPublicId_returns404() throws Exception {
        when(adminActorResolver.resolve(any())).thenReturn(ADMIN_ID);
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/admin/claims/" + CLAIM_PUBLIC_ID + "/approve").header("X-Admin-Id", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        verify(claimService, never()).approveByAdmin(anyLong(), any());
    }

    @Test
    @DisplayName("T5 승인: REQUESTED 아님 → 422 CLAIM_STATE_INVALID(CLM-4)")
    void approve_invalidState_returns422() throws Exception {
        Claim claim = mockClaim(ClaimStatus.COMPLETED);
        when(adminActorResolver.resolve(any())).thenReturn(ADMIN_ID);
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.of(claim));
        doThrow(new ClaimInvalidStateException("불법 클레임 상태 전이"))
                .when(claimService).approveByAdmin(eq(CLAIM_ID), any());

        mockMvc.perform(post("/api/v1/admin/claims/" + CLAIM_PUBLIC_ID + "/approve").header("X-Admin-Id", "1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));
    }

    // ===== POST /api/v1/admin/claims/{claimPublicId}/reject =====

    @Test
    @DisplayName("T6 거부: 정상 → 200 + 갱신된 ClaimResponse(REJECTED)·rejectByAdmin 위임")
    void reject_returns200() throws Exception {
        Claim claim = mockClaim(ClaimStatus.REJECTED);
        OrderItem orderItem = mockOrderItem();
        when(adminActorResolver.resolve(any())).thenReturn(ADMIN_ID);
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.of(claim));
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));

        mockMvc.perform(post("/api/v1/admin/claims/" + CLAIM_PUBLIC_ID + "/reject").header("X-Admin-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(CLAIM_PUBLIC_ID))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        verify(claimService).rejectByAdmin(eq(CLAIM_ID), any());
    }
}
