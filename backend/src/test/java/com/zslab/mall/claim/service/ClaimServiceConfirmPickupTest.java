package com.zslab.mall.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimPickedUp;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ClaimService} 수거 확인(confirmPickup·D-98 Q1·Q9) 단위 검증. primitive 정상/멱등/미발견/비APPROVED·Seller/Admin
 * wrapper 권한 경계를 mock 경계에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ClaimServiceConfirmPickupTest {

    private static final Long CLAIM_ID = 1L;
    private static final Long ORDER_ITEM_ID = 10L;
    private static final Long SELLER_ID = 200L;
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 6, 29, 9, 0);
    private static final LocalDateTime PROCESSED_AT = LocalDateTime.of(2026, 6, 29, 10, 0);
    private static final LocalDateTime PICKED_UP_AT = LocalDateTime.of(2026, 6, 29, 11, 0);

    @Mock
    private ClaimRepository claimRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private TracedEventPublisher eventPublisher;
    @InjectMocks
    private ClaimService claimService;

    /** APPROVED·RETURN Claim 시드(picked_up_at null). */
    private Claim approvedReturnClaim() {
        Claim claim = Claim.create(ORDER_ITEM_ID, ClaimType.RETURN, ClaimReasonCode.PRODUCT_DEFECT.name(),
                "하자", 100L, REQUESTED_AT, OrderItemStatus.DELIVERED);
        ReflectionTestUtils.setField(claim, "id", CLAIM_ID);
        claim.approve(PROCESSED_AT, null); // REQUESTED → APPROVED
        return claim;
    }

    @Test
    @DisplayName("confirmPickup: APPROVED·picked_up_at null → pickedUpAt 설정·save·ClaimPickedUp 발행")
    void confirmPickup_approved_setsAndPublishes() {
        Claim claim = approvedReturnClaim();
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));

        claimService.confirmPickup(CLAIM_ID, PICKED_UP_AT);

        assertThat(claim.getPickedUpAt()).isEqualTo(PICKED_UP_AT);
        verify(claimRepository).save(claim);
        verify(eventPublisher).publishEvent(any(ClaimPickedUp.class));
    }

    @Test
    @DisplayName("confirmPickup: 이미 picked_up_at 설정 → 멱등 no-op(save·publish 없음)")
    void confirmPickup_alreadyPickedUp_idempotentNoOp() {
        Claim claim = approvedReturnClaim();
        ReflectionTestUtils.setField(claim, "pickedUpAt", PICKED_UP_AT.minusHours(1));
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));

        claimService.confirmPickup(CLAIM_ID, PICKED_UP_AT);

        verify(claimRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("confirmPickup: 클레임 미존재 → ClaimNotFoundException")
    void confirmPickup_notFound_throws() {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.confirmPickup(CLAIM_ID, PICKED_UP_AT))
                .isInstanceOf(ClaimNotFoundException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("confirmPickup: 비APPROVED(REQUESTED) → ClaimInvalidStateException(CLM-4)")
    void confirmPickup_notApproved_throws() {
        Claim claim = Claim.create(ORDER_ITEM_ID, ClaimType.RETURN, ClaimReasonCode.PRODUCT_DEFECT.name(),
                "하자", 100L, REQUESTED_AT, OrderItemStatus.DELIVERED); // REQUESTED 상태
        ReflectionTestUtils.setField(claim, "id", CLAIM_ID);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> claimService.confirmPickup(CLAIM_ID, PICKED_UP_AT))
                .isInstanceOf(ClaimInvalidStateException.class);
        verify(claimRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("confirmPickupBySeller: 소유 판매자 → primitive 위임·발행")
    void confirmPickupBySeller_authorized_delegates() {
        Claim claim = approvedReturnClaim();
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getSellerId()).thenReturn(SELLER_ID);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));

        claimService.confirmPickupBySeller(CLAIM_ID, SELLER_ID, PICKED_UP_AT);

        assertThat(claim.getPickedUpAt()).isEqualTo(PICKED_UP_AT);
        verify(eventPublisher).publishEvent(any(ClaimPickedUp.class));
    }

    @Test
    @DisplayName("confirmPickupBySeller: 타 판매자 → ClaimNotFoundException(404 은닉)·발행 없음")
    void confirmPickupBySeller_otherSeller_throws() {
        Claim claim = approvedReturnClaim();
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getSellerId()).thenReturn(999L);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));

        assertThatThrownBy(() -> claimService.confirmPickupBySeller(CLAIM_ID, SELLER_ID, PICKED_UP_AT))
                .isInstanceOf(ClaimNotFoundException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("confirmPickupByAdmin: 권한 검증 없이 primitive 위임·발행·authorizeSellerAccess 미진입")
    void confirmPickupByAdmin_delegates() {
        Claim claim = approvedReturnClaim();
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));

        claimService.confirmPickupByAdmin(CLAIM_ID, PICKED_UP_AT);

        verify(eventPublisher).publishEvent(any(ClaimPickedUp.class));
        verify(orderItemRepository, never()).findById(any());
    }
}
