package com.zslab.mall.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.notification.enums.NotificationChannel;
import com.zslab.mall.notification.enums.NotificationLogStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NotificationLog} 상태 전이 단위 검증(Track 19·D-86 §후속). markSent/markFailed의 PENDING → SENT/FAILED 전이와
 * PENDING이 아닌 상태에서의 IllegalStateException 가드(중복 전이 방지)를 검증한다.
 */
class NotificationLogTest {

    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 7, 1, 10, 0);

    private static NotificationLog pendingLog() {
        return NotificationLog.create(1L, NotificationChannel.EMAIL, "TPL_ORDER_PLACED",
                PolymorphicTargetType.ORDER, 100L, "제목", "내용");
    }

    @Test
    @DisplayName("markSent: PENDING → SENT 전이·sentAt 기록·failedReason NULL 유지")
    void markSent_fromPending_transitionsToSent() {
        NotificationLog notificationLog = pendingLog();

        notificationLog.markSent(SENT_AT);

        assertThat(notificationLog.getStatus()).isEqualTo(NotificationLogStatus.SENT);
        assertThat(notificationLog.getSentAt()).isEqualTo(SENT_AT);
        assertThat(notificationLog.getFailedReason()).isNull();
    }

    @Test
    @DisplayName("markFailed: PENDING → FAILED 전이·failedReason 기록·sentAt NULL 유지")
    void markFailed_fromPending_transitionsToFailed() {
        NotificationLog notificationLog = pendingLog();

        notificationLog.markFailed("SMTP timeout");

        assertThat(notificationLog.getStatus()).isEqualTo(NotificationLogStatus.FAILED);
        assertThat(notificationLog.getFailedReason()).isEqualTo("SMTP timeout");
        assertThat(notificationLog.getSentAt()).isNull();
    }

    @Test
    @DisplayName("markSent: 이미 SENT 상태에서 재호출 → IllegalStateException(중복 전이 방지)")
    void markSent_notPending_throws() {
        NotificationLog notificationLog = pendingLog();
        notificationLog.markSent(SENT_AT);

        assertThatThrownBy(() -> notificationLog.markSent(SENT_AT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markFailed: 이미 SENT 상태에서 호출 → IllegalStateException(PENDING 가드)")
    void markFailed_notPending_throws() {
        NotificationLog notificationLog = pendingLog();
        notificationLog.markSent(SENT_AT);

        assertThatThrownBy(() -> notificationLog.markFailed("late failure"))
                .isInstanceOf(IllegalStateException.class);
    }
}
