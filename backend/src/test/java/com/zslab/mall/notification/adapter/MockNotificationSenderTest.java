package com.zslab.mall.notification.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.notification.entity.NotificationLog;
import com.zslab.mall.notification.enums.NotificationChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link MockNotificationSender} 단위 검증(Track 19). 외부 호출 없이 발송 사실을 INFO 로그 1줄로 남기고 예외 없이
 * 성공 반환하는지 Logback {@link ListAppender}로 확인한다.
 */
class MockNotificationSenderTest {

    private final MockNotificationSender sender = new MockNotificationSender();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(MockNotificationSender.class);
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("send: 외부 호출 없이 INFO 발송 모사 로그 1줄(channel·template 포함)·예외 없음")
    void send_logsDispatchSimulation() {
        NotificationLog notificationLog = NotificationLog.create(777L, NotificationChannel.EMAIL,
                "TPL_ORDER_PLACED", PolymorphicTargetType.ORDER, 100L, "주문 접수", "내용");

        sender.send(notificationLog);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .contains("[MockNotificationSender]")
                .contains("EMAIL")
                .contains("TPL_ORDER_PLACED");
    }
}
