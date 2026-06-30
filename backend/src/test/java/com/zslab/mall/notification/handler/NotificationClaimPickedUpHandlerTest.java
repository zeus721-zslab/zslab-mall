package com.zslab.mall.notification.handler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimPickedUp;
import com.zslab.mall.notification.service.NotificationService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link NotificationClaimPickedUpHandler} 단위 검증(D-98 Q8). recordClaimPickedUp 위임·서비스 예외 비전파(D-95 Q7)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationClaimPickedUpHandlerTest {

    private static final LocalDateTime PICKED_UP_AT = LocalDateTime.of(2026, 6, 29, 11, 0);

    @Mock
    private NotificationService notificationService;
    @InjectMocks
    private NotificationClaimPickedUpHandler handler;

    private ClaimPickedUp event() {
        return new ClaimPickedUp(1L, "clm_x", 10L, ClaimType.RETURN, PICKED_UP_AT, PICKED_UP_AT);
    }

    @Test
    @DisplayName("handle: recordClaimPickedUp 위임")
    void delegatesToService() {
        ClaimPickedUp event = event();

        handler.handle(event);

        verify(notificationService).recordClaimPickedUp(event);
    }

    @Test
    @DisplayName("handle: 서비스 예외 → 비전파(structured log·D-95 Q7)")
    void serviceThrows_noPropagation() {
        ClaimPickedUp event = event();
        doThrow(new RuntimeException("적재 실패")).when(notificationService).recordClaimPickedUp(event);

        handler.handle(event); // 예외가 전파되지 않으면 통과
    }
}
