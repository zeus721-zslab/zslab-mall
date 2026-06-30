package com.zslab.mall.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Track 16 D-100 Q14 차단 조건 해소·MDC 자연 상속 실측 단위 테스트.
 *
 * <p><b>의도</b>: Spring @TransactionalEventListener(AFTER_COMMIT) + REQUIRES_NEW 핸들러 진입 시
 * MDC 키(traceId·correlationId·eventName)가 발행처 컨텍스트로부터 자연 상속되는지 실측한다.
 * nested publishEvent (핸들러 내 추가 이벤트 발행) 2단계 상속 동반 실측.
 *
 * <p><b>D-100 Q14 박제</b>: 자연 상속 확인 시 MdcContextCopier helper 미신설·미상속 발견 시 신설.
 * 본 테스트 결과로 PR-1 helper 신설 여부 결정.
 *
 * <p><b>스코프</b>: HTTP 미경유·DB 미접근·LT-02·D-91 시드 불요. AFTER_COMMIT 핸들러 자체의
 * MDC 상속 메커니즘만 검증한다. 스레드 전환(@Async·Scheduler·Outbox·Executor)은 본 테스트 범위 외.
 *
 * <p><b>격리</b>: 운영 빈 미오염을 위해 {@link TestConfig}만 로드한다(@SpringBootApplication 스캔·auto-config
 * 미사용 → DataSource 없음). AFTER_COMMIT 동기화·REQUIRES_NEW를 같은 스레드에서 구동하기 위한 무자원
 * {@link ResourcelessTransactionManager}를 주입한다. 모든 핸들러는 발행 스레드와 동일 스레드에서 동기 실행되며,
 * MDC는 thread-local이므로 본 테스트는 그 자연 상속 여부 자체를 검증한다.
 */
@SpringBootTest(classes = {MdcPropagationTest.TestConfig.class})
class MdcPropagationTest {

    // traceId는 운영 com.zslab.mall.common.web.TraceIdFilter.TRACE_ID와 동일 키.
    // correlationId·eventName은 PR-1에서 도입 예정 키로 본 테스트가 발행처에서 수동 주입한다.
    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_EVENT_NAME = "eventName";

    private static final String TRACE_ID_VALUE = "test-trace-001";
    private static final String EVENT_NAME_VALUE = "TestOuterEvent";

    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private TestOuterEventHandler outerHandler;
    @Autowired
    private TestNestedEventHandler nestedHandler;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        outerHandler.reset();
        nestedHandler.reset();
        MDC.put(MDC_TRACE_ID, TRACE_ID_VALUE);
        MDC.put(MDC_CORRELATION_ID, TRACE_ID_VALUE);
        MDC.put(MDC_EVENT_NAME, EVENT_NAME_VALUE);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("케이스1 AFTER_COMMIT 핸들러에 발행처 MDC(traceId·correlationId·eventName) 자연 상속")
    void mdc_is_inherited_in_after_commit_handler() {
        tx.executeWithoutResult(status -> publisher.publishEvent(new TestOuterEvent("p1")));

        CapturedMdc captured = outerHandler.getCaptured();
        assertThat(captured).isNotNull();
        assertThat(captured.traceId()).isEqualTo(TRACE_ID_VALUE);
        assertThat(captured.correlationId()).isEqualTo(TRACE_ID_VALUE);
        assertThat(captured.eventName()).isEqualTo(EVENT_NAME_VALUE);
    }

    @Test
    @DisplayName("케이스2 nested publishEvent 2단계 AFTER_COMMIT 핸들러에 MDC 자연 상속(덮어쓰기 없음)")
    void mdc_is_inherited_in_nested_after_commit_handler() {
        tx.executeWithoutResult(status -> publisher.publishEvent(new TestOuterEvent("p1")));

        CapturedMdc captured = nestedHandler.getCaptured();
        assertThat(captured).isNotNull();
        assertThat(captured.traceId()).isEqualTo(TRACE_ID_VALUE);
        assertThat(captured.correlationId()).isEqualTo(TRACE_ID_VALUE);
        // Outer 핸들러가 MDC를 덮어쓰지 않으므로 발행처가 set한 eventName이 그대로 상속된다.
        assertThat(captured.eventName()).isEqualTo(EVENT_NAME_VALUE);
    }

    // ---------- 테스트용 이벤트·핸들러·캡처 (도메인 이벤트와 무관·테스트 격리) ----------

    public record TestOuterEvent(String payload) {}

    public record TestNestedEvent(String payload) {}

    /** 핸들러 진입 시점 MDC 3종 스냅샷. */
    record CapturedMdc(String traceId, String correlationId, String eventName) {
        static CapturedMdc snapshot() {
            return new CapturedMdc(
                    MDC.get(MDC_TRACE_ID), MDC.get(MDC_CORRELATION_ID), MDC.get(MDC_EVENT_NAME));
        }
    }

    /**
     * 운영 핸들러(예: ClaimRequestedHandler)와 동일하게 AFTER_COMMIT + REQUIRES_NEW로 실행되며, 진입 시점
     * MDC를 캡처한 뒤 nested 이벤트를 추가 발행한다. 단일 스레드 동기 실행이므로 상태 필드는 volatile로 충분하다.
     */
    static class TestOuterEventHandler {

        private final ApplicationEventPublisher publisher;
        private volatile CapturedMdc captured;

        TestOuterEventHandler(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void onOuter(TestOuterEvent event) {
            this.captured = CapturedMdc.snapshot();
            // REQUIRES_NEW 커밋 후 nested 핸들러를 AFTER_COMMIT으로 트리거 (2단계 상속 실측용).
            publisher.publishEvent(new TestNestedEvent(event.payload()));
        }

        CapturedMdc getCaptured() {
            return captured;
        }

        void reset() {
            this.captured = null;
        }
    }

    /** Outer 핸들러가 발행한 nested 이벤트를 동일 AFTER_COMMIT + REQUIRES_NEW로 소비하며 진입 시점 MDC를 캡처한다. */
    static class TestNestedEventHandler {

        private volatile CapturedMdc captured;

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void onNested(TestNestedEvent event) {
            this.captured = CapturedMdc.snapshot();
        }

        CapturedMdc getCaptured() {
            return captured;
        }

        void reset() {
            this.captured = null;
        }
    }

    // @TestConfiguration이 아닌 @Configuration: classes로 단독 지정 시 Spring Boot가 @TestConfiguration을
    // 1차 설정으로 인정하지 않고 @SpringBootApplication(ZslabMallApplication·Flyway·JPA·DB)으로 폴백하므로,
    // 운영 빈 미오염·DB 미접근 격리를 실제로 달성하려면 @Configuration이어야 한다.
    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        TestOuterEventHandler testOuterEventHandler(ApplicationEventPublisher publisher) {
            return new TestOuterEventHandler(publisher);
        }

        @Bean
        TestNestedEventHandler testNestedEventHandler() {
            return new TestNestedEventHandler();
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new ResourcelessTransactionManager();
        }
    }

    /**
     * DB 없이 트랜잭션 동기화·AFTER_COMMIT 콜백·REQUIRES_NEW(동기화 일시중단/재개)만 구동하는 무자원 매니저.
     * 실제 자원이 없으므로 begin·commit·rollback은 no-op이며, isExistingTransaction 기본값(false)에 의해 매
     * getTransaction이 새 트랜잭션을 시작한다 → suspend는 동기화 목록만 대상으로 하므로 doSuspend 미구현으로 충분하다.
     */
    static class ResourcelessTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // 무자원 — 시작 시 확보할 자원 없음.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // 무자원 — 커밋할 자원 없음. AFTER_COMMIT 동기화는 상위 클래스가 트리거한다.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // 무자원 — 롤백할 자원 없음.
        }
    }
}
