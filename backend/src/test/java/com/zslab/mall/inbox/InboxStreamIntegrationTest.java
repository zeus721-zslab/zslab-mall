package com.zslab.mall.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthCookies;
import com.zslab.mall.inbox.stream.InboxSignalPublisher;
import com.zslab.mall.inbox.stream.InboxStreamRegistry;
import com.zslab.mall.inbox.stream.InboxStreamRegistryTestAccess;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 인박스 변경 신호 SSE 연결·인가·커밋 경계·범위·ASYNC 디스패치 통합 테스트(D-249·실 서버·실 MariaDB).
 *
 * <p><b>시드</b>: 셀러 A(구성원 A1) · 셀러 B(구성원 B1) · 정지 셀러 S(구성원 S1) · 종료 셀러 T(구성원 T1). 관리자는 user 행 없이 토큰만 쓴다
 * (AuthenticatedUserStateVerifier가 행 없음을 통과시키는 픽스처 관례). 시드는 ? 바인딩(SQL injection 없음).
 */
@ExtendWith(OutputCaptureExtension.class)
class InboxStreamIntegrationTest extends InboxStreamTestSupport {

    private static final Duration CLOSE_WAIT = Duration.ofSeconds(MAX_DURATION_SECONDS + 6L);

    private static final long ADMIN_ID = 949001L;
    private static final long SELLER_A = 949001L;
    private static final long SELLER_B = 949002L;
    private static final long SELLER_SUSPENDED = 949003L;
    private static final long SELLER_TERMINATED = 949004L;
    private static final long USER_A1 = 949011L;
    private static final long USER_B1 = 949012L;
    private static final long USER_S1 = 949013L;
    private static final long USER_T1 = 949014L;

    @Autowired
    private InboxSignalPublisher inboxSignalPublisher;
    @Autowired
    private InboxStreamRegistry inboxStreamRegistry;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seed();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("S1 연결: 관리자·셀러 쿠키 200 · text/event-stream · X-Accel-Buffering: no · 첫 주석 줄 즉시 수신")
    void connect() throws Exception {
        for (SseStream stream : List.of(open(ADMIN_STREAM, ActorRole.ADMIN, ADMIN_ID), open(SELLER_STREAM, ActorRole.SELLER, USER_A1))) {
            assertThat(stream.status()).isEqualTo(200);
            assertThat(stream.response().headers().firstValue("Content-Type")).hasValueSatisfying(
                    contentType -> assertThat(contentType).startsWith("text/event-stream"));
            assertThat(stream.response().headers().firstValue(InboxStreamRegistry.NO_BUFFERING_HEADER)).hasValue("no");
            assertThat(stream.awaitLine(HEARTBEAT_LINE, SIGNAL_WAIT)).isTrue();
        }
    }

    @Test
    @DisplayName("S2 인가: 미인증 401 · 역할 쿠키 격리 401 · 쿠키 이름과 토큰 역할 불일치 401 · 종료 셀러 401 · 정지 셀러는 조회와 같이 200")
    void authorization() throws Exception {
        assertThat(open(ADMIN_STREAM, null, 0L).status()).isEqualTo(401);
        assertThat(open(SELLER_STREAM, null, 0L).status()).isEqualTo(401);
        // 쿠키 이름 격리: 경로마다 그 역할 쿠키만 읽으므로 다른 역할 쿠키는 익명과 같다(엔드포인트 존재는 S1의 200이 보인다).
        assertThat(openWithCookie(ADMIN_STREAM, AuthCookies.SELLER_COOKIE, ActorRole.SELLER, USER_A1).status()).isEqualTo(401);
        assertThat(openWithCookie(SELLER_STREAM, AuthCookies.ADMIN_COOKIE, ActorRole.ADMIN, ADMIN_ID).status()).isEqualTo(401);
        // 쿠키 이름만 바꾼 우회: 경로에 맞는 쿠키 이름에 다른 역할 토큰을 실으면 역할 불일치로 익명 → 401
        assertThat(openWithCookie(ADMIN_STREAM, AuthCookies.ADMIN_COOKIE, ActorRole.SELLER, USER_A1).status()).isEqualTo(401);
        assertThat(openWithCookie(SELLER_STREAM, AuthCookies.SELLER_COOKIE, ActorRole.ADMIN, ADMIN_ID).status()).isEqualTo(401);
        assertThat(open(SELLER_STREAM, ActorRole.SELLER, USER_T1).status()).isEqualTo(401);
        SseStream suspended = open(SELLER_STREAM, ActorRole.SELLER, USER_S1);
        assertThat(suspended.status()).isEqualTo(200);
        assertThat(suspended.awaitLine(HEARTBEAT_LINE, SIGNAL_WAIT)).isTrue();
    }

    @Test
    @DisplayName("S3 커밋 후 수신 · 롤백 시 미수신")
    void commitAndRollback() throws Exception {
        SseStream admin = openReady(ADMIN_STREAM, ActorRole.ADMIN, ADMIN_ID);

        tx.executeWithoutResult(status -> {
            inboxSignalPublisher.adminChanged();
            status.setRollbackOnly();
        });
        assertThat(admin.awaitChanged(SILENCE_WAIT)).isFalse();

        tx.executeWithoutResult(status -> inboxSignalPublisher.adminChanged());
        assertThat(admin.awaitChanged(SIGNAL_WAIT)).isTrue();
    }

    @Test
    @DisplayName("S4 한 트랜잭션의 여러 발행 → 범위별 1신호")
    void coalescePerCommit() throws Exception {
        SseStream admin = openReady(ADMIN_STREAM, ActorRole.ADMIN, ADMIN_ID);
        SseStream sellerA = openReady(SELLER_STREAM, ActorRole.SELLER, USER_A1);

        tx.executeWithoutResult(status -> {
            inboxSignalPublisher.adminChanged();
            inboxSignalPublisher.sellerChanged(SELLER_A);
            inboxSignalPublisher.adminChanged();
            inboxSignalPublisher.sellerChanged(SELLER_A);
            inboxSignalPublisher.allSellersChanged();
        });

        assertThat(admin.countChanged(SILENCE_WAIT)).isEqualTo(1);
        assertThat(sellerA.countChanged(SILENCE_WAIT)).isEqualTo(1);
    }

    @Test
    @DisplayName("S5 셀러 범위: A 지정 신호는 B·관리자가 받지 않음 · sellerId 없는 발행은 셀러 전체 수신 · 트랜잭션 밖 호출은 즉시")
    void sellerScope() throws Exception {
        SseStream admin = openReady(ADMIN_STREAM, ActorRole.ADMIN, ADMIN_ID);
        SseStream sellerA = openReady(SELLER_STREAM, ActorRole.SELLER, USER_A1);
        SseStream sellerB = openReady(SELLER_STREAM, ActorRole.SELLER, USER_B1);

        tx.executeWithoutResult(status -> inboxSignalPublisher.sellerChanged(SELLER_A));
        assertThat(sellerA.awaitChanged(SIGNAL_WAIT)).isTrue();
        assertThat(sellerB.awaitChanged(SILENCE_WAIT)).isFalse();
        assertThat(admin.awaitChanged(Duration.ZERO)).isFalse();

        inboxSignalPublisher.allSellersChanged();
        assertThat(sellerA.awaitChanged(SIGNAL_WAIT)).isTrue();
        assertThat(sellerB.awaitChanged(SIGNAL_WAIT)).isTrue();
        assertThat(admin.awaitChanged(SILENCE_WAIT)).isFalse();
    }

    @Test
    @DisplayName("S6 ASYNC 디스패치: 최대 수명 도달(타임아웃)·서버 complete(종료 경로) 모두 401/403/500 없이 스트림이 정상 종료")
    void asyncDispatchAfterTimeoutAndComplete(CapturedOutput output) throws Exception {
        SseStream timedOut = openReady(ADMIN_STREAM, ActorRole.ADMIN, ADMIN_ID);
        SseStream sellerTimedOut = openReady(SELLER_STREAM, ActorRole.SELLER, USER_A1);
        assertThat(timedOut.awaitLine(END_OF_STREAM, CLOSE_WAIT)).isTrue();
        assertThat(sellerTimedOut.awaitLine(END_OF_STREAM, CLOSE_WAIT)).isTrue();

        SseStream completed = openReady(ADMIN_STREAM, ActorRole.ADMIN, ADMIN_ID);
        inboxStreamRegistry.stop();
        try {
            assertThat(completed.awaitLine(END_OF_STREAM, SIGNAL_WAIT)).isTrue();
        } finally {
            inboxStreamRegistry.start();
        }

        for (SseStream stream : List.of(timedOut, sellerTimedOut, completed)) {
            assertThat(stream.lines()).noneMatch(line -> line.contains("UNAUTHENTICATED") || line.contains("FORBIDDEN")
                    || line.contains("\"status\""));
        }
        assertThat(output.getAll()).doesNotContain("AccessDenied", "Access Denied", "AsyncRequestTimeoutException",
                "Servlet.service()", "미분류 서버 오류");
    }

    @Test
    @DisplayName("S7 클라이언트가 떠난 연결: 다음 전송에서 정리되고(연결 수 N → N+1 → N) 미분류 서버 오류(500 ERROR)로 남지 않음")
    void clientDisconnect(CapturedOutput output) throws Exception {
        // 앞 테스트가 닫은 연결은 다음 쓰기 또는 최대 수명에서 정리된다 — 하트비트로 정리하며 기준이 안정될 때까지 기다린다.
        Awaitility.await().atMost(Duration.ofSeconds(MAX_DURATION_SECONDS + 4L)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            InboxStreamRegistryTestAccess.sendHeartbeat(inboxStreamRegistry);
            assertThat(InboxStreamRegistryTestAccess.connectionCount(inboxStreamRegistry)).isZero();
        });
        int before = InboxStreamRegistryTestAccess.connectionCount(inboxStreamRegistry);

        SseStream admin = openReady(ADMIN_STREAM, ActorRole.ADMIN, ADMIN_ID);
        assertThat(InboxStreamRegistryTestAccess.connectionCount(inboxStreamRegistry)).isEqualTo(before + 1);
        admin.close();

        // 끊김은 쓰기 시점에 드러나므로 신호를 보내며 기다린다.
        Awaitility.await().atMost(SIGNAL_WAIT.multipliedBy(3)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            inboxSignalPublisher.adminChanged();
            assertThat(output.getAll()).contains("[Web] 비동기 응답 클라이언트 연결 끊김");
            assertThat(InboxStreamRegistryTestAccess.connectionCount(inboxStreamRegistry)).isEqualTo(before);
        });
        assertThat(output.getAll()).doesNotContain("미분류 서버 오류", "Servlet.service()");
    }

    private void seed() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSellerWithMember(SELLER_A, USER_A1, "ISTA", "ACTIVE");
                seedSellerWithMember(SELLER_B, USER_B1, "ISTB", "ACTIVE");
                seedSellerWithMember(SELLER_SUSPENDED, USER_S1, "ISTS", "SUSPENDED");
                seedSellerWithMember(SELLER_TERMINATED, USER_T1, "ISTT", "TERMINATED");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSellerWithMember(long sellerId, long userId, String tag, String sellerStatus) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', ?, NOW(6), NOW(6))", sellerId, pid("slr_", tag), "스트림셀러" + tag, sellerStatus);
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                userId, pid("usr_", tag));
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", userId, sellerId);
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM seller_user WHERE user_id BETWEEN ? AND ?", USER_A1, USER_T1);
                jdbc.update("DELETE FROM `user` WHERE id BETWEEN ? AND ?", USER_A1, USER_T1);
                jdbc.update("DELETE FROM seller WHERE id BETWEEN ? AND ?", SELLER_A, SELLER_TERMINATED);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}
