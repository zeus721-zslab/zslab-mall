package com.zslab.mall.review.handler;

import com.zslab.mall.common.observability.EventMetricsRecorder;
import com.zslab.mall.review.event.ReviewChangedEvent;
import com.zslab.mall.review.service.ReviewSummaryService;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ReviewChangedEvent → 한 줄 요약 재계산(Track 106-1·결정 6). 커밋 후({@code AFTER_COMMIT})에 받아 이 핸들러가 소유한 전용 단일 스레드 실행기에
 * 넘긴다 — 요청 스레드·요청 트랜잭션은 요약 계산(향후 실제 모델 호출)을 기다리지 않고, 재계산은 직렬이라 같은 상품의 동시 upsert 경쟁이 없다.
 * 롤백된 쓰기는 이벤트가 전달되지 않는다.
 *
 * <p><b>실행기를 빈이 아닌 필드로 두는 이유</b>: Executor 빈을 추가하면 Spring Boot 기본 {@code applicationTaskExecutor} 자동 구성이 물러날 수 있어
 * 다른 비동기 사용처에 영향이 없도록 이 컴포넌트 안에 가둔다. 종료 시 {@link #destroy}에서 멈춘다.
 *
 * <p><b>실패 처리</b>: 재계산 예외·큐 초과는 요청에 전파하지 않고 표준 키 warn 1줄 + {@code zslab.event.failed} 계측만 남긴다(기존 AFTER_COMMIT
 * 핸들러와 같다). 요약은 다음 변경 때 다시 계산되며, 그 사이에는 직전 요약이 보인다.
 *
 * <p>요약은 권위 데이터가 아니다(표시용 파생값). 큐 초과·LLM 실패 시 다음 변경까지 오래된 요약을 허용하므로 재시도·보상 큐를 두지 않는다.
 */
@Slf4j
@Component
public class ReviewSummaryHandler implements DisposableBean {

    /** 대기 재계산 상한. 넘치면 버리고 warn — 요약은 다음 변경 때 다시 맞춰진다. */
    static final int QUEUE_CAPACITY = 1_000;
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;
    private static final String THREAD_NAME_PREFIX = "zslab-review-summary-";

    private final ReviewSummaryService reviewSummaryService;
    private final EventMetricsRecorder eventMetricsRecorder;
    private final ThreadPoolExecutor executor;

    public ReviewSummaryHandler(ReviewSummaryService reviewSummaryService, EventMetricsRecorder eventMetricsRecorder) {
        this.reviewSummaryService = reviewSummaryService;
        this.eventMetricsRecorder = eventMetricsRecorder;
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ReviewChangedEvent event) {
        Map<String, String> requestContext = MDC.getCopyOfContextMap();
        try {
            executor.execute(() -> recalculate(event, requestContext));
        } catch (RejectedExecutionException rejected) {
            // 큐 초과·종료 중: 요청 흐름을 막지 않기 위해 흡수하고 기록만 남긴다(다음 변경 때 재계산).
            logFailure(event, rejected);
        }
    }

    private void recalculate(ReviewChangedEvent event, Map<String, String> requestContext) {
        if (requestContext != null) {
            MDC.setContextMap(requestContext);
        }
        try {
            reviewSummaryService.recalculate(event.productId());
        } catch (RuntimeException exception) {
            // 요약은 부가 정보라 실패해도 리뷰 쓰기는 이미 커밋됐다. 흡수하고 운영자가 볼 수 있게 남긴다.
            logFailure(event, exception);
        } finally {
            MDC.clear();
        }
    }

    private void logFailure(ReviewChangedEvent event, RuntimeException exception) {
        log.warn("[ReviewSummary] event={} target_type={} target_id={} action=manual_review correlationId={} handler={}",
                "ReviewChangedEvent", "PRODUCT", event.productId(), MDC.get("correlationId"), getClass().getSimpleName(), exception);
        eventMetricsRecorder.recordFailed(event.getClass().getSimpleName());
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("[ReviewSummary] 종료 대기 초과 — 남은 재계산 {}건을 버린다(다음 변경 때 재계산)", executor.getQueue().size());
            executor.shutdownNow();
        }
    }
}
