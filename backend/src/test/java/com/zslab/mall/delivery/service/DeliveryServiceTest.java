package com.zslab.mall.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.event.DeliveryCompleted;
import com.zslab.mall.delivery.event.DeliveryStarted;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link DeliveryService} 단위 검증(Track 13·D-97 Q2·Q3). 발송·배송 완료 도메인 전이와 E4·E5 발행(D-29 save→publish),
 * canTransitionTo 위반·DLV-3 위반 차단을 mock 경계에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    private static final Long DELIVERY_ID = 1L;
    private static final Long ORDER_ITEM_ID = 10L;
    private static final String TRACKING_NO = "CJ-TRACK-0001";

    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @InjectMocks
    private DeliveryService deliveryService;

    @Test
    @DisplayName("markShipping: READY → SHIPPING 전이·trackingNo·shippedAt 설정·DeliveryStarted 발행(D-29)")
    void markShipping_ready_transitionsAndPublishes() {
        Delivery delivery = Delivery.create(ORDER_ITEM_ID, DeliveryCarrier.CJ);
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));

        deliveryService.markShipping(DELIVERY_ID, TRACKING_NO);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SHIPPING);
        assertThat(delivery.getTrackingNo()).isEqualTo(TRACKING_NO);
        assertThat(delivery.getShippedAt()).isNotNull();
        verify(deliveryRepository).save(delivery);
        ArgumentCaptor<DeliveryStarted> captor = ArgumentCaptor.forClass(DeliveryStarted.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().orderItemId()).isEqualTo(ORDER_ITEM_ID);
        assertThat(captor.getValue().carrier()).isEqualTo(DeliveryCarrier.CJ);
        assertThat(captor.getValue().trackingNo()).isEqualTo(TRACKING_NO);
    }

    @Test
    @DisplayName("markShipping: DELIVERED 상태 → canTransitionTo 위반·IllegalStateException·미발행")
    void markShipping_delivered_throwsAndDoesNotPublish() {
        Delivery delivery = Delivery.create(ORDER_ITEM_ID, DeliveryCarrier.CJ);
        delivery.markShipping(TRACKING_NO, LocalDateTime.now());
        delivery.markDelivered(LocalDateTime.now());
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> deliveryService.markShipping(DELIVERY_ID, TRACKING_NO))
                .isInstanceOf(IllegalStateException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("markDelivered: SHIPPING → DELIVERED 전이·deliveredAt 설정·DeliveryCompleted 발행(D-29)")
    void markDelivered_shipping_transitionsAndPublishes() {
        Delivery delivery = Delivery.create(ORDER_ITEM_ID, DeliveryCarrier.CJ);
        delivery.markShipping(TRACKING_NO, LocalDateTime.now());
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));

        deliveryService.markDelivered(DELIVERY_ID);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivery.getDeliveredAt()).isNotNull();
        verify(deliveryRepository).save(delivery);
        ArgumentCaptor<DeliveryCompleted> captor = ArgumentCaptor.forClass(DeliveryCompleted.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().orderItemId()).isEqualTo(ORDER_ITEM_ID);
        assertThat(captor.getValue().deliveredAt()).isNotNull();
    }

    @Test
    @DisplayName("markDelivered: shipped_at > delivered_at → DLV-3 위반·IllegalStateException·미발행(WARN-7)")
    void markDelivered_dlv3Violation_throwsAndDoesNotPublish() {
        Delivery delivery = Delivery.create(ORDER_ITEM_ID, DeliveryCarrier.CJ);
        // shippedAt을 미래로 설정해 service의 markDelivered(now)가 DLV-3을 위반하도록 한다.
        delivery.markShipping(TRACKING_NO, LocalDateTime.now().plusDays(1));
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> deliveryService.markDelivered(DELIVERY_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DLV-3");
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SHIPPING);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("markDelivered: READY 상태(SHIPPING 스킵) → canTransitionTo 위반·IllegalStateException·미발행")
    void markDelivered_ready_throwsAndDoesNotPublish() {
        Delivery delivery = Delivery.create(ORDER_ITEM_ID, DeliveryCarrier.CJ);
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> deliveryService.markDelivered(DELIVERY_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.READY);
        verify(eventPublisher, never()).publishEvent(any());
    }
}
