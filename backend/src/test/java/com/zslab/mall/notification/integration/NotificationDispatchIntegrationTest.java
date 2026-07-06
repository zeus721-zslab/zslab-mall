package com.zslab.mall.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.notification.adapter.NotificationSender;
import com.zslab.mall.order.event.OrderPlaced;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * 발송 어댑터 dispatch E2E 통합 테스트(Track 19·D-86 §후속·실 MariaDB·Flyway). OrderPlaced(E1)를 커밋 트랜잭션에서 발행 →
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 알림 핸들러 → REQUIRES_NEW에서 NotificationLog 적재 후 즉시 발송
 * (save/dispatch 분리·판단 2 α)까지 실제 커밋 경로로 검증한다.
 *
 * <p><b>트랜잭션(NotificationLogIntegrationTest 패턴·D-90 Q5 β)</b>: AFTER_COMMIT 핸들러는 커밋 후에만 실행되므로 클래스에
 * {@code @Transactional}을 두지 않는다. 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}으로 커밋하고,
 * 검증은 {@link JdbcTemplate} 직접 조회로 한다. 발행도 {@link TransactionTemplate} 안에서 수행해 그 커밋 시점에 핸들러가
 * 동기적으로 발화한다(@Async 아님).
 *
 * <p><b>발송 결과 주입</b>: {@link NotificationSender}를 {@link MockitoBean}으로 대체한다. T1은 기본 no-op(성공)으로 SENT
 * 전이를, T2·T3은 {@code doThrow}로 FAILED 전이·계측·비차단을 결정적으로 검증한다.
 *
 * <p><b>카운터 delta 단언(EventFailedMetricIntegrationTest 패턴)</b>: MeterRegistry는 컨텍스트 캐싱으로 테스트 간 카운터가
 * 누적되므로 절대값 대신 호출 전후 차이로 단언한다.
 *
 * <p><b>LT-02</b>: {@code SET FOREIGN_KEY_CHECKS=0}은 try-finally로 {@code =1} 복원과 1:1 짝을 이룬다.
 */
class NotificationDispatchIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9319L;
    private static final long ORDER_ID = 9319L;
    private static final long FULL_AMOUNT = 10_000L;

    private static final String ORDER_PID = pid("ord_", "T19ORD");
    private static final String EVENT_NAME = "OrderPlaced";
    private static final String CHANNEL = "EMAIL";
    private static final String FAIL_REASON = "mock send failure";

    @MockitoBean
    private NotificationSender notificationSender;
    @Autowired
    private TracedEventPublisher eventPublisher;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedOrder();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 OrderPlaced 발행 → 발송 성공 → NotificationLog SENT 전이·sent_at 기록·failed_reason NULL")
    void orderPlaced_dispatchSuccess_marksSent() {
        // notificationSender.send는 @MockitoBean 기본 no-op(성공)이라 별도 stub이 필요 없다.
        tx.executeWithoutResult(s -> eventPublisher.publishEvent(
                new OrderPlaced(ORDER_PID, ORDER_ID, LocalDateTime.now())));

        assertThat(count()).isEqualTo(1);
        assertThat(value("status")).isEqualTo("SENT");
        assertThat(value("sent_at")).isNotNull();
        assertThat(value("failed_reason")).isNull();
    }

    @Test
    @DisplayName("T2 발송 실패 주입 → NotificationLog FAILED 전이·failed_reason 저장·zslab.notification.failed 계측·원 주문 커밋 유지")
    void orderPlaced_dispatchFailure_marksFailed_recordsMetric() {
        doThrow(new RuntimeException(FAIL_REASON)).when(notificationSender).send(any());
        double failedBefore = notificationFailedCount(EVENT_NAME, CHANNEL);

        tx.executeWithoutResult(s -> eventPublisher.publishEvent(
                new OrderPlaced(ORDER_PID, ORDER_ID, LocalDateTime.now())));

        assertThat(count()).isEqualTo(1);
        assertThat(value("status")).isEqualTo("FAILED");
        assertThat(value("failed_reason")).isEqualTo(FAIL_REASON);
        assertThat(value("sent_at")).isNull();
        assertThat(notificationFailedCount(EVENT_NAME, CHANNEL) - failedBefore)
                .as("zslab.notification.failed{event=OrderPlaced, channel=EMAIL} 증가량").isEqualTo(1.0);
        // 원 주문(상위 트랜잭션)은 발송 실패와 무관하게 커밋 유지.
        assertThat(orderExists()).isTrue();
    }

    @Test
    @DisplayName("T3 발송 실패 → dispatch 내부 흡수(재throw 없음)·핸들러 catch 미진입(zslab.event.failed 무증가)·트랜잭션 커밋 유지")
    void orderPlaced_dispatchFailure_doesNotRethrow_handlerCatchNotEntered() {
        doThrow(new RuntimeException(FAIL_REASON)).when(notificationSender).send(any());
        double eventFailedBefore = eventFailedCount(EVENT_NAME);
        double notificationFailedBefore = notificationFailedCount(EVENT_NAME, CHANNEL);

        tx.executeWithoutResult(s -> eventPublisher.publishEvent(
                new OrderPlaced(ORDER_PID, ORDER_ID, LocalDateTime.now())));

        // dispatch가 예외를 내부 흡수 → NotificationLog는 FAILED로 커밋(서비스·핸들러 상위로 재throw 없음).
        assertThat(value("status")).isEqualTo("FAILED");
        // 핸들러 catch 미진입: 이벤트 축(zslab.event.failed)은 무증가.
        assertThat(eventFailedCount(EVENT_NAME) - eventFailedBefore)
                .as("핸들러 catch 미진입 → zslab.event.failed 무증가").isZero();
        // 발송 축(zslab.notification.failed)만 증가.
        assertThat(notificationFailedCount(EVENT_NAME, CHANNEL) - notificationFailedBefore)
                .as("발송 축만 계측").isEqualTo(1.0);
        // 상위 트랜잭션(주문)은 커밋 유지.
        assertThat(orderExists()).isTrue();
    }

    // ---------- seed·helpers ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /** recordOrderPlaced는 order 재조회로 buyer를 recipient로 산정하므로 user·order만 시드한다(FK 비활성·D-91·LT-02). */
    private void seedOrder() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "T19USR"));
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, ORDER_PID, USER_ID, "ORDT19" + ORDER_ID, FULL_AMOUNT);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE target_type = 'ORDER' AND target_id = ?", ORDER_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private int count() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE target_type = 'ORDER' AND target_id = ?",
                Integer.class, ORDER_ID);
    }

    private String value(String column) {
        // column은 테스트 상수 리터럴('status'·'sent_at'·'failed_reason')만 전달·외부 입력 아님(SQL injection 위험 없음).
        return jdbc.queryForObject(
                "SELECT " + column + " FROM notification_log WHERE target_type = 'ORDER' AND target_id = ?",
                String.class, ORDER_ID);
    }

    private boolean orderExists() {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE id = ?", Integer.class, ORDER_ID);
        return rows != null && rows > 0;
    }

    private double notificationFailedCount(String eventName, String channel) {
        Counter counter = meterRegistry.find("zslab.notification.failed")
                .tag("event", eventName).tag("channel", channel).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double eventFailedCount(String eventName) {
        Counter counter = meterRegistry.find("zslab.event.failed").tag("event", eventName).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
