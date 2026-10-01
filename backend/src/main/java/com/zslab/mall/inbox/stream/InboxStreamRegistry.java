package com.zslab.mall.inbox.stream;

import com.zslab.mall.inbox.enums.InboxAudience;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * 인박스 변경 신호 SSE 연결 목록과 전송(D-249). 백엔드 1대 전제라 연결은 메모리에만 둔다.
 *
 * <p><b>전송 스레드</b>: 신호·하트비트·정기 신호는 이 컴포넌트가 소유한 전용 단일 스레드 실행기에서 보낸다 — 커밋 스레드·스케줄러 스레드가 느린 연결을
 * 기다리지 않고, 전송 실패가 본 처리를 실패시키지 않는다. Executor 빈을 추가하면 Boot 기본 {@code applicationTaskExecutor}가 물러날 수 있어
 * 필드로 둔다(ReviewSummaryHandler와 같은 이유).
 *
 * <p><b>종료</b>: graceful shutdown은 진행 중 요청(열린 스트림 포함)을 기다리므로, 웹 서버 graceful 단계보다 먼저 멈추는 phase에서 열린 연결을 모두
 * complete한다. 그 뒤 들어온 연결은 바로 닫는다.
 */
@Slf4j
@Component
public class InboxStreamRegistry implements SmartLifecycle, DisposableBean {

    /** nginx가 이 응답을 모아 두지 않게 하는 응답 헤더(gateway 설정 변경 없이 스트림을 바로 흘린다). */
    public static final String NO_BUFFERING_HEADER = "X-Accel-Buffering";
    static final String CHANGED_EVENT = "changed";
    /** EventSource는 data가 빈 이벤트를 전달하지 않으므로 최소 1글자를 싣는다. 내용은 의미가 없다. */
    private static final String CHANGED_DATA = "1";
    private static final String HEARTBEAT_COMMENT = "ping";
    private static final long HEARTBEAT_SECONDS = 25L;
    /** 쓰기 없이 바뀌는 항목(장기 배송 진입·보류 만료 등)을 재조회하게 하는 정기 신호 주기. */
    private static final long PERIODIC_SIGNAL_HOURS = 1L;
    private static final int QUEUE_CAPACITY = 1_000;
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;
    private static final String THREAD_NAME_PREFIX = "zslab-inbox-stream-";

    private final long maxDurationMillis;
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final ThreadPoolExecutor executor;
    private volatile boolean running;

    public InboxStreamRegistry(@Value("${zslab.inbox.stream.max-duration}") Duration maxDuration) {
        this.maxDurationMillis = maxDuration.toMillis();
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 관리자 연결을 연다. 인가는 SecurityConfig {@code /api/v1/admin/**}가 마친 뒤다. */
    public SseEmitter openForAdmin() {
        return open(InboxAudience.ADMIN, null);
    }

    /** 셀러 연결을 연다. 호출 전에 셀러 상태 가드를 통과한 sellerId여야 한다. */
    public SseEmitter openForSeller(long sellerId) {
        return open(InboxAudience.SELLER, sellerId);
    }

    private SseEmitter open(InboxAudience audience, Long sellerId) {
        SseEmitter emitter = new SseEmitter(maxDurationMillis);
        Connection connection = new Connection(audience, sellerId, emitter);
        emitter.onCompletion(() -> connections.remove(connection));
        emitter.onError(error -> connections.remove(connection));
        // 최대 수명 도달은 정상 종료로 닫는다 — 그대로 두면 타임아웃 예외가 오류 응답 경로로 간다. 클라이언트는 재연결한다.
        emitter.onTimeout(() -> {
            connections.remove(connection);
            emitter.complete();
        });
        connections.add(connection);
        if (!running) {
            // 종료 단계에서 stop()의 일괄 complete 뒤에 들어온 연결은 바로 닫는다.
            connections.remove(connection);
            emitter.complete();
            return emitter;
        }
        // 첫 바이트를 바로 보내 응답 헤더를 flush한다(프록시·브라우저가 연결 성립을 즉시 안다).
        send(connection, InboxStreamRegistry::heartbeat);
        return emitter;
    }

    /** 커밋된 변경을 범위 안의 연결에 알린다. 전송은 전용 스레드에서 하므로 호출자를 막지 않는다. */
    void broadcast(InboxSignal signal) {
        dispatch(connection -> signal.reaches(connection.audience(), connection.sellerId()), InboxStreamRegistry::changed);
    }

    /** 프록시 유휴 타임아웃(로컬 gateway 60s)보다 짧게 주석 줄을 보낸다. 끊긴 연결도 여기서 걸러진다. */
    @Scheduled(fixedRate = HEARTBEAT_SECONDS, initialDelay = HEARTBEAT_SECONDS, timeUnit = TimeUnit.SECONDS)
    void sendHeartbeat() {
        dispatch(connection -> true, InboxStreamRegistry::heartbeat);
    }

    @Scheduled(fixedRate = PERIODIC_SIGNAL_HOURS, initialDelay = PERIODIC_SIGNAL_HOURS, timeUnit = TimeUnit.HOURS)
    void sendPeriodicSignal() {
        dispatch(connection -> true, InboxStreamRegistry::changed);
    }

    private void dispatch(Predicate<Connection> target, Supplier<SseEventBuilder> event) {
        try {
            executor.execute(() -> connections.stream().filter(target).forEach(connection -> send(connection, event)));
        } catch (RejectedExecutionException rejected) {
            // 큐 초과·종료 중: 신호는 다음 변경·하트비트·정기 신호로 다시 간다. 호출자(커밋 후 처리)를 실패시키지 않는다.
            log.warn("[InboxStream] 전송 작업 거부 — 이번 전송을 버린다 queued={}", executor.getQueue().size(), rejected);
        }
    }

    private void send(Connection connection, Supplier<SseEventBuilder> event) {
        try {
            connection.emitter().send(event.get());
        } catch (IOException | IllegalStateException failed) {
            // 끊긴·이미 닫힌 연결: 목록에서 빼고 넘어간다(정리는 컨테이너의 오류·완료 콜백이 마친다).
            connections.remove(connection);
            log.warn("[InboxStream] 전송 실패로 연결 제거 audience={} sellerId={} reason={}",
                    connection.audience(), connection.sellerId(), failed.toString());
        }
    }

    /** 열린 연결 수(같은 패키지 테스트의 누수 확인용). */
    int connectionCount() {
        return connections.size();
    }

    /** SseEventBuilder는 build()가 내부 버퍼를 덧붙이므로 전송마다 새로 만든다. */
    private static SseEventBuilder changed() {
        return SseEmitter.event().name(CHANGED_EVENT).data(CHANGED_DATA);
    }

    private static SseEventBuilder heartbeat() {
        return SseEmitter.event().comment(HEARTBEAT_COMMENT);
    }

    @Override
    public void start() {
        running = true;
    }

    /** 기본 phase(Integer.MAX_VALUE)라 웹 서버 graceful shutdown 단계보다 먼저 불린다. */
    @Override
    public void stop() {
        running = false;
        for (Connection connection : connections) {
            connection.emitter().complete();
        }
        connections.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("[InboxStream] 종료 대기 초과 — 남은 전송 {}건을 버린다", executor.getQueue().size());
            executor.shutdownNow();
        }
    }

    /** @param sellerId 셀러 연결의 소속 셀러(관리자 연결은 null) */
    private record Connection(InboxAudience audience, Long sellerId, SseEmitter emitter) {
    }
}
