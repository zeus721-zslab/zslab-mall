package com.zslab.mall.notification.handler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimApproved;
import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.notification.service.NotificationService;
import com.zslab.mall.order.event.OrderPlaced;
import com.zslab.mall.payment.event.PaymentCompleted;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 알림 핸들러 4건 단위 검증(Track 12·D-95 Q3·Q7). 각 핸들러가 {@link NotificationService}의 대응 record 메서드를
 * 1회 위임하는 정상 경로와, Service 예외가 핸들러 밖으로 전파되지 않는 실패 격리(structured log catch)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationHandlerTest {

    private static final Long ORDER_ID = 50L;
    private static final Long ORDER_ITEM_ID = 10L;
    private static final Long CLAIM_ID = 1L;
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 30, 9, 0);

    @Mock
    private NotificationService notificationService;
    @InjectMocks
    private NotificationOrderPlacedHandler orderPlacedHandler;
    @InjectMocks
    private NotificationPaymentCompletedHandler paymentCompletedHandler;
    @InjectMocks
    private NotificationClaimApprovedHandler claimApprovedHandler;
    @InjectMocks
    private NotificationClaimCompletedHandler claimCompletedHandler;

    private OrderPlaced orderPlaced() {
        return new OrderPlaced("ord_pub1", ORDER_ID, OCCURRED_AT);
    }

    private PaymentCompleted paymentCompleted() {
        return new PaymentCompleted(99L, ORDER_ID, 12_000L, "pgtx1", OCCURRED_AT);
    }

    private ClaimApproved claimApproved() {
        return new ClaimApproved(CLAIM_ID, "clm_pub1", ORDER_ITEM_ID, ClaimType.CANCEL,
                ClaimStatus.APPROVED, OCCURRED_AT);
    }

    private ClaimCompleted claimCompleted() {
        return new ClaimCompleted(CLAIM_ID, "clm_pub1", ORDER_ITEM_ID, ClaimType.CANCEL,
                ClaimStatus.COMPLETED, OCCURRED_AT);
    }

    // ---------- OrderPlaced ----------

    @Test
    @DisplayName("NotificationOrderPlacedHandler: 정상 → recordOrderPlaced 1회 위임")
    void orderPlaced_delegates() {
        OrderPlaced event = orderPlaced();
        doNothing().when(notificationService).recordOrderPlaced(event);

        orderPlacedHandler.handle(event);

        verify(notificationService).recordOrderPlaced(event);
    }

    @Test
    @DisplayName("NotificationOrderPlacedHandler: Service 예외 → catch·재throw 안 함")
    void orderPlaced_exceptionIsolated() {
        OrderPlaced event = orderPlaced();
        doThrow(new RuntimeException("boom")).when(notificationService).recordOrderPlaced(event);

        assertThatCode(() -> orderPlacedHandler.handle(event)).doesNotThrowAnyException();
    }

    // ---------- PaymentCompleted ----------

    @Test
    @DisplayName("NotificationPaymentCompletedHandler: 정상 → recordPaymentCompleted 1회 위임")
    void paymentCompleted_delegates() {
        PaymentCompleted event = paymentCompleted();
        doNothing().when(notificationService).recordPaymentCompleted(event);

        paymentCompletedHandler.handle(event);

        verify(notificationService).recordPaymentCompleted(event);
    }

    @Test
    @DisplayName("NotificationPaymentCompletedHandler: Service 예외 → catch·재throw 안 함")
    void paymentCompleted_exceptionIsolated() {
        PaymentCompleted event = paymentCompleted();
        doThrow(new RuntimeException("boom")).when(notificationService).recordPaymentCompleted(event);

        assertThatCode(() -> paymentCompletedHandler.handle(event)).doesNotThrowAnyException();
    }

    // ---------- ClaimApproved ----------

    @Test
    @DisplayName("NotificationClaimApprovedHandler: 정상 → recordClaimApproved 1회 위임")
    void claimApproved_delegates() {
        ClaimApproved event = claimApproved();
        doNothing().when(notificationService).recordClaimApproved(event);

        claimApprovedHandler.handle(event);

        verify(notificationService).recordClaimApproved(event);
    }

    @Test
    @DisplayName("NotificationClaimApprovedHandler: Service 예외 → catch·재throw 안 함")
    void claimApproved_exceptionIsolated() {
        ClaimApproved event = claimApproved();
        doThrow(new RuntimeException("boom")).when(notificationService).recordClaimApproved(event);

        assertThatCode(() -> claimApprovedHandler.handle(event)).doesNotThrowAnyException();
    }

    // ---------- ClaimCompleted ----------

    @Test
    @DisplayName("NotificationClaimCompletedHandler: 정상 → recordClaimCompleted 1회 위임")
    void claimCompleted_delegates() {
        ClaimCompleted event = claimCompleted();
        doNothing().when(notificationService).recordClaimCompleted(event);

        claimCompletedHandler.handle(event);

        verify(notificationService).recordClaimCompleted(event);
    }

    @Test
    @DisplayName("NotificationClaimCompletedHandler: Service 예외 → catch·재throw 안 함")
    void claimCompleted_exceptionIsolated() {
        ClaimCompleted event = claimCompleted();
        doThrow(new RuntimeException("boom")).when(notificationService).recordClaimCompleted(event);

        assertThatCode(() -> claimCompletedHandler.handle(event)).doesNotThrowAnyException();
    }
}
