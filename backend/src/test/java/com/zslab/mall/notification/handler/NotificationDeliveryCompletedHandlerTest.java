package com.zslab.mall.notification.handler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.event.DeliveryCompleted;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.notification.service.NotificationService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link NotificationDeliveryCompletedHandler} 단위 검증(Track 14 PR-2·D-98 Q5·외부 검토 2차 R1). 일반 배송은 알림을
 * 적재하고, 교환 배송({@code delivery.claimId != null})은 E9 ClaimCompleted 경로에 위임하여 적재하지 않으며, 적재 예외는
 * 핸들러 밖으로 전파하지 않음을 mock 경계에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDeliveryCompletedHandlerTest {

    private static final Long DELIVERY_ID = 1L;
    private static final Long ORDER_ITEM_ID = 10L;
    private static final Long CLAIM_ID = 77L;
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 30, 9, 0);

    @Mock
    private NotificationService notificationService;
    @Mock
    private DeliveryRepository deliveryRepository;
    @InjectMocks
    private NotificationDeliveryCompletedHandler handler;

    private DeliveryCompleted event() {
        return new DeliveryCompleted(DELIVERY_ID, ORDER_ITEM_ID, OCCURRED_AT, OCCURRED_AT);
    }

    @Test
    @DisplayName("handle: 일반 배송(claim_id == null) → recordDeliveryCompleted 적재 위임")
    void handle_normalDelivery_records() {
        Delivery delivery = org.mockito.Mockito.mock(Delivery.class);
        when(delivery.getClaimId()).thenReturn(null);
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));

        handler.handle(event());

        verify(notificationService).recordDeliveryCompleted(any(DeliveryCompleted.class));
    }

    @Test
    @DisplayName("handle: 교환 배송(claim_id != null) → 적재 미위임(E9 경로 위임·R1)")
    void handle_exchangeDelivery_skipsRecord() {
        Delivery delivery = org.mockito.Mockito.mock(Delivery.class);
        when(delivery.getClaimId()).thenReturn(CLAIM_ID);
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));

        handler.handle(event());

        verify(notificationService, never()).recordDeliveryCompleted(any());
    }

    @Test
    @DisplayName("handle: Delivery 미발견 → 기존 적재 경로 통과(recordDeliveryCompleted 위임)")
    void handle_deliveryNotFound_records() {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.empty());

        handler.handle(event());

        verify(notificationService).recordDeliveryCompleted(any(DeliveryCompleted.class));
    }

    @Test
    @DisplayName("handle: 적재 예외 → log.warn 후 핸들러 밖 전파 없음(실패 격리)")
    void handle_recordThrows_isolatesFailure() {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("적재 실패")).when(notificationService).recordDeliveryCompleted(any());

        assertThatCode(() -> handler.handle(event())).doesNotThrowAnyException();
    }
}
