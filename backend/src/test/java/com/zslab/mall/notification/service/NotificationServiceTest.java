package com.zslab.mall.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimApproved;
import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.notification.entity.NotificationLog;
import com.zslab.mall.notification.enums.NotificationChannel;
import com.zslab.mall.notification.enums.NotificationLogStatus;
import com.zslab.mall.notification.repository.NotificationLogRepository;
import com.zslab.mall.notification.template.NotificationTemplateCodes;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.event.OrderPlaced;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.payment.event.PaymentCompleted;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link NotificationService} 단위 검증(Track 12·D-95 Q5 α·A1-α·A2-α). 이벤트별 정상 적재(재조회 → create → save)와
 * 재조회 미발견 skip(NULL 적재 회피·save 0건)을 mock 경계에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Long ORDER_ID = 50L;
    private static final Long ORDER_ITEM_ID = 10L;
    private static final Long CLAIM_ID = 1L;
    private static final Long BUYER_ID = 777L;
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 30, 9, 0);

    @Mock
    private NotificationLogRepository notificationLogRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private ClaimRepository claimRepository;
    @InjectMocks
    private NotificationService notificationService;

    // ---------- OrderPlaced ----------

    @Test
    @DisplayName("recordOrderPlaced: Order 존재 → ORDER target·TPL_ORDER_PLACED·PENDING·EMAIL 적재")
    void recordOrderPlaced_orderFound_saves() {
        Order order = Order.create(BUYER_ID, "ORDNO1", 0L, 0L);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        notificationService.recordOrderPlaced(new OrderPlaced("ord_pub1", ORDER_ID, OCCURRED_AT));

        NotificationLog saved = captureSaved();
        assertThat(saved.getRecipientUserId()).isEqualTo(BUYER_ID);
        assertThat(saved.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(saved.getTemplateCode()).isEqualTo(NotificationTemplateCodes.ORDER_PLACED);
        assertThat(saved.getTargetType()).isEqualTo(PolymorphicTargetType.ORDER);
        assertThat(saved.getTargetId()).isEqualTo(ORDER_ID);
        assertThat(saved.getStatus()).isEqualTo(NotificationLogStatus.PENDING);
        assertThat(saved.getTitle()).isEqualTo("주문 접수");
        assertThat(saved.getContent()).contains("ord_pub1");
    }

    @Test
    @DisplayName("recordOrderPlaced: Order 미존재 → skip(save 0건)")
    void recordOrderPlaced_orderMissing_skips() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        notificationService.recordOrderPlaced(new OrderPlaced("ord_pub1", ORDER_ID, OCCURRED_AT));

        verify(notificationLogRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ---------- PaymentCompleted ----------

    @Test
    @DisplayName("recordPaymentCompleted: Order 존재 → ORDER target·TPL_PAYMENT_COMPLETED 적재·금액 content 포함")
    void recordPaymentCompleted_orderFound_saves() {
        Order order = Order.create(BUYER_ID, "ORDNO1", 0L, 0L);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        notificationService.recordPaymentCompleted(
                new PaymentCompleted(99L, ORDER_ID, 12_000L, "pgtx1", OCCURRED_AT));

        NotificationLog saved = captureSaved();
        assertThat(saved.getTemplateCode()).isEqualTo(NotificationTemplateCodes.PAYMENT_COMPLETED);
        assertThat(saved.getTargetType()).isEqualTo(PolymorphicTargetType.ORDER);
        assertThat(saved.getTargetId()).isEqualTo(ORDER_ID);
        assertThat(saved.getTitle()).isEqualTo("결제 완료");
        assertThat(saved.getContent()).contains("12000");
    }

    @Test
    @DisplayName("recordPaymentCompleted: Order 미존재 → skip(save 0건)")
    void recordPaymentCompleted_orderMissing_skips() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        notificationService.recordPaymentCompleted(
                new PaymentCompleted(99L, ORDER_ID, 12_000L, "pgtx1", OCCURRED_AT));

        verify(notificationLogRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ---------- ClaimApproved ----------

    @Test
    @DisplayName("recordClaimApproved: Claim·OrderItem·Order 존재 → CLAIM target·TPL_CLAIM_APPROVED 적재")
    void recordClaimApproved_resolved_saves() {
        seedClaimChain();

        notificationService.recordClaimApproved(claimApproved());

        NotificationLog saved = captureSaved();
        assertThat(saved.getRecipientUserId()).isEqualTo(BUYER_ID);
        assertThat(saved.getTemplateCode()).isEqualTo(NotificationTemplateCodes.CLAIM_APPROVED);
        assertThat(saved.getTargetType()).isEqualTo(PolymorphicTargetType.CLAIM);
        assertThat(saved.getTargetId()).isEqualTo(CLAIM_ID);
        assertThat(saved.getTitle()).isEqualTo("클레임 승인");
        assertThat(saved.getContent()).contains("clm_pub1");
    }

    @Test
    @DisplayName("recordClaimApproved: Claim 미존재 → skip(save 0건·A1-α)")
    void recordClaimApproved_claimMissing_skips() {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

        notificationService.recordClaimApproved(claimApproved());

        verify(notificationLogRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("recordClaimApproved: OrderItem 미존재(orderId 해소 실패) → skip(save 0건·A1-α)")
    void recordClaimApproved_orderItemMissing_skips() {
        Claim claim = Claim.create(ORDER_ITEM_ID, ClaimType.CANCEL, "CHANGE_MIND", null, BUYER_ID, OCCURRED_AT, OrderItemStatus.PAID);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.empty());

        notificationService.recordClaimApproved(claimApproved());

        verify(notificationLogRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ---------- ClaimCompleted ----------

    @Test
    @DisplayName("recordClaimCompleted: Claim·OrderItem·Order 존재 → CLAIM target·TPL_CLAIM_COMPLETED 적재")
    void recordClaimCompleted_resolved_saves() {
        seedClaimChain();

        notificationService.recordClaimCompleted(claimCompleted());

        NotificationLog saved = captureSaved();
        assertThat(saved.getTemplateCode()).isEqualTo(NotificationTemplateCodes.CLAIM_COMPLETED);
        assertThat(saved.getTargetType()).isEqualTo(PolymorphicTargetType.CLAIM);
        assertThat(saved.getTargetId()).isEqualTo(CLAIM_ID);
        assertThat(saved.getTitle()).isEqualTo("클레임 완료");
    }

    @Test
    @DisplayName("recordClaimCompleted: Claim 미존재 → skip(save 0건)")
    void recordClaimCompleted_claimMissing_skips() {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

        notificationService.recordClaimCompleted(claimCompleted());

        verify(notificationLogRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ---------- helpers ----------

    private void seedClaimChain() {
        Claim claim = Claim.create(ORDER_ITEM_ID, ClaimType.CANCEL, "CHANGE_MIND", null, BUYER_ID, OCCURRED_AT, OrderItemStatus.PAID);
        Order order = Order.create(BUYER_ID, "ORDNO1", 0L, 0L);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
    }

    private ClaimApproved claimApproved() {
        return new ClaimApproved(CLAIM_ID, "clm_pub1", ORDER_ITEM_ID, ClaimType.CANCEL,
                ClaimStatus.APPROVED, OCCURRED_AT);
    }

    private ClaimCompleted claimCompleted() {
        return new ClaimCompleted(CLAIM_ID, "clm_pub1", ORDER_ITEM_ID, ClaimType.CANCEL,
                ClaimStatus.COMPLETED, OCCURRED_AT);
    }

    private NotificationLog captureSaved() {
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        return captor.getValue();
    }
}
