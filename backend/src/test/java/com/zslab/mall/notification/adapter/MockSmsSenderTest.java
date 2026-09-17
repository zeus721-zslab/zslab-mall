package com.zslab.mall.notification.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link MockSmsSender} 단위 검증(Track 80 D-169). 도메인 객체 없이 수신번호·본문만으로 발송을 모사하고, 로그에는 번호가
 * 마스킹({@code 010-****-1234})되어 원문이 남지 않는지 Logback {@link ListAppender}로 확인한다.
 */
class MockSmsSenderTest {

    private final MockSmsSender sender = new MockSmsSender();

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(MockSmsSender.class);
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
    @DisplayName("send: 수신번호·본문만으로 INFO 로그 1줄·번호는 마스킹(원문 미노출)·본문은 길이만(Track 84)·예외 없음")
    void send_logsMaskedNumber() {
        String content = "[zslab-mall] 주문 ORD1 상품A 취소 요청이 접수되었습니다.";
        sender.send("010-1234-5678", content);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .contains("[MockSmsSender]")
                .contains("to=010-****-5678")
                .contains("contentLength=" + content.length())
                .doesNotContain("취소 요청이 접수되었습니다.")
                .doesNotContain("010-1234-5678")
                .doesNotContain("1234-5678");
    }

    @Test
    @DisplayName("send: 수신번호 없음 → IllegalArgumentException·로그 없음")
    void send_withoutNumber_throws() {
        assertThatThrownBy(() -> sender.send(" ", "본문")).isInstanceOf(IllegalArgumentException.class);
        assertThat(appender.list).isEmpty();
    }
}
