package com.zslab.mall.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zslab.mall.refund.enums.RefundCallbackStatus;
import com.zslab.mall.refund.exception.RefundNotFoundException;
import com.zslab.mall.refund.service.RefundService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * {@link MockRefundAutoCallbackListener} 단위 검증(Track 80 D-169·C4). 기존 콜백 서비스를 SUCCESS로 호출하고, 콜백 실패는 전파하지
 * 않고 error 로그만 남기는지(Refund PENDING 유지·요청 TX 비영향) 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class MockRefundAutoCallbackListenerTest {

    private static final String PG_REFUND_ID = "mock_rfn_test0001";

    @Mock
    private RefundService refundService;

    private MockRefundAutoCallbackListener listener;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        listener = new MockRefundAutoCallbackListener(refundService);
        logger = (Logger) LoggerFactory.getLogger(MockRefundAutoCallbackListener.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("onMockRefundAccepted: handleCallback(pgRefundId, SUCCESS, null) 1회 호출")
    void callsHandleCallbackSuccess() {
        listener.onMockRefundAccepted(new MockRefundAccepted(PG_REFUND_ID));

        verify(refundService).handleCallback(eq(PG_REFUND_ID), eq(RefundCallbackStatus.SUCCESS), isNull());
    }

    @Test
    @DisplayName("onMockRefundAccepted: 콜백 예외 → 전파 없음·ERROR 로그 1줄(PENDING 유지 안내)")
    void callbackFailure_isIsolatedWithErrorLog() {
        doThrow(new RefundNotFoundException("환불 행을 찾을 수 없습니다"))
                .when(refundService).handleCallback(eq(PG_REFUND_ID), eq(RefundCallbackStatus.SUCCESS), isNull());

        assertThatCode(() -> listener.onMockRefundAccepted(new MockRefundAccepted(PG_REFUND_ID))).doesNotThrowAnyException();

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("PENDING 유지").contains(PG_REFUND_ID);
        });
    }
}
