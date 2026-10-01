package com.zslab.mall.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthCookies;
import com.zslab.mall.common.security.TokenProvider;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 인박스 SSE 통합 테스트 공통(D-249). MockMvc는 비동기 디스패치를 실제로 돌리지 않으므로 실 서버(RANDOM_PORT)에 HTTP로 붙어 스트림을 읽는다.
 * 연결 최대 수명은 타임아웃 경로(ASYNC 디스패치)를 실측하도록 짧게 둔다. 데이터를 바꾸는 스케줄러는 꺼서 의도하지 않은 신호를 막는다.
 * 두 하위 클래스가 같은 설정을 써 컨텍스트를 공유한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "zslab.inbox.stream.max-duration=" + InboxStreamTestSupport.MAX_DURATION_SECONDS + "s",
        "zslab.order.auto-cancel.enabled=false",
        "zslab.order.auto-confirm.enabled=false",
        "zslab.order.expired-cleanup.enabled=false",
        "zslab.payment.expiry.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.attachment.cleanup.enabled=false"
})
abstract class InboxStreamTestSupport extends AbstractIntegrationTest {

    /** 한 테스트의 연결·호출·침묵 판정이 끝나기에 충분하되 타임아웃 경로 실측(S6)이 길어지지 않는 값. */
    static final int MAX_DURATION_SECONDS = 8;
    static final String ADMIN_STREAM = "/api/v1/admin/inbox/stream";
    static final String SELLER_STREAM = "/api/v1/seller/inbox/stream";
    static final Duration SIGNAL_WAIT = Duration.ofSeconds(3);
    /** 신호가 오지 않음을 판정하는 대기. 전송은 전용 스레드에서 즉시 나가므로 이 정도면 충분하다. */
    static final Duration SILENCE_WAIT = Duration.ofMillis(1_500);
    static final String CHANGED_LINE = "event:changed";
    static final String HEARTBEAT_LINE = ":ping";
    static final String END_OF_STREAM = "\u0000EOF";

    @LocalServerPort
    private int port;
    @Autowired
    private TokenProvider tokenProvider;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final List<SseStream> openStreams = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeStreams() {
        openStreams.forEach(SseStream::close);
    }

    /** 200을 받고 첫 주석 줄까지 읽은 스트림. 첫 줄을 받았으면 연결 목록에 등록된 뒤다. */
    SseStream openReady(String path, ActorRole role, long actorId) throws Exception {
        SseStream stream = open(path, role, actorId);
        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.awaitLine(HEARTBEAT_LINE, SIGNAL_WAIT)).isTrue();
        return stream;
    }

    /** @param role 역할 쿠키(null이면 쿠키 없음) */
    SseStream open(String path, ActorRole role, long actorId) throws Exception {
        if (role == null) {
            return openWithCookie(path, null, null, actorId);
        }
        String cookieName = switch (role) {
            case BUYER -> AuthCookies.BUYER_COOKIE;
            case SELLER -> AuthCookies.SELLER_COOKIE;
            case ADMIN -> AuthCookies.ADMIN_COOKIE;
        };
        return openWithCookie(path, cookieName, role, actorId);
    }

    SseStream openWithCookie(String path, String cookieName, ActorRole tokenRole, long actorId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/event-stream").GET();
        if (cookieName != null) {
            request.header("Cookie", cookieName + "=" + tokenProvider.issue(actorId, tokenRole));
        }
        HttpResponse<InputStream> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        SseStream stream = new SseStream(response);
        openStreams.add(stream);
        return stream;
    }

    static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }

    /** 응답 본문을 별도 스레드에서 줄 단위로 읽어 쌓는다. 끝나면 {@link #END_OF_STREAM}을 넣는다. */
    static final class SseStream {

        private final HttpResponse<InputStream> response;
        private final LinkedBlockingQueue<String> pending = new LinkedBlockingQueue<>();
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final Thread reader;

        SseStream(HttpResponse<InputStream> response) {
            this.response = response;
            this.reader = Thread.ofVirtual().start(this::readAll);
        }

        private void readAll() {
            try (BufferedReader body = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = body.readLine()) != null) {
                    lines.add(line);
                    pending.add(line);
                }
            } catch (IOException closed) {
                // 테스트가 연결을 먼저 끊은 경우 — 끝 표시만 남긴다.
                lines.add("IOException: " + closed.getMessage());
            } finally {
                pending.add(END_OF_STREAM);
            }
        }

        int status() {
            return response.statusCode();
        }

        HttpResponse<InputStream> response() {
            return response;
        }

        List<String> lines() {
            return lines;
        }

        boolean awaitChanged(Duration wait) throws InterruptedException {
            return awaitLine(CHANGED_LINE, wait);
        }

        /** 대기 시간 동안 받은 변경 신호 수. */
        int countChanged(Duration wait) throws InterruptedException {
            int count = 0;
            while (awaitChanged(wait)) {
                count++;
            }
            return count;
        }

        /**
         * 기다리는 줄이 올 때까지 앞의 줄을 소비한다. 다른 줄을 기다리는데 스트림이 끝나면 실패시킨다 — 끝난 스트림의 "신호 없음"은 침묵 판정이 될 수
         * 없다(거짓 통과 방지).
         */
        boolean awaitLine(String expected, Duration wait) throws InterruptedException {
            long deadline = System.nanoTime() + wait.toNanos();
            while (true) {
                long remaining = deadline - System.nanoTime();
                String line = pending.poll(Math.max(remaining, 0L), TimeUnit.NANOSECONDS);
                if (line == null) {
                    return false;
                }
                if (line.equals(expected)) {
                    return true;
                }
                if (line.equals(END_OF_STREAM)) {
                    throw new AssertionError("'" + expected + "'를 기다리는 중 스트림이 끝났다: " + lines);
                }
            }
        }

        void close() {
            try {
                response.body().close();
            } catch (IOException alreadyClosed) {
                // 이미 끊긴 스트림 — 정리 단계라 결과에 영향이 없다.
                lines.add("close: " + alreadyClosed.getMessage());
            }
            reader.interrupt();
        }
    }
}
