package com.zslab.mall.delivery.scheduler;

import com.zslab.mall.delivery.adapter.DeliveryTracker;
import com.zslab.mall.delivery.adapter.DeliveryTrackingStatus;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.delivery.repository.DeliveryTrackingCandidate;
import com.zslab.mall.delivery.service.DeliveryAutoCompleteService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 배송중 배송을 택배 조회 결과에 따라 자동으로 배송완료 처리하는 배치 스케줄러(Track 99 D-210·{@code OrderAutoConfirmScheduler} 원형 준용).
 * 트랜잭션을 갖지 않으며 오케스트레이션만 담당한다 — 후보를 페이지로 읽고, 트랜잭션 밖에서 {@link DeliveryTracker}에 물은 뒤,
 * 배달 완료인 건만 {@link DeliveryAutoCompleteService#completeOne}(각자 독립 트랜잭션)에 넘긴다.
 *
 * <p><b>커서 순회</b>: id 커서로 배송중을 훑는다({@link #PAGE_SIZE}씩·한 실행 최대 {@link #MAX_PER_RUN}건). 커서는 결과와 무관하게
 * 훑은 행마다 전진하므로, 아직 배달되지 않은 건이 앞 페이지를 계속 차지해 뒤쪽 건이 영영 조회되지 않는 일이 없다.
 *
 * <p><b>커서는 실행 사이에 유지된다</b>({@link #cursor}). 상한에 걸려 중간에 끊기면 다음 실행이 그 위치부터 이어받고, 끝에 도달하면
 * 0으로 되돌려 처음부터 다시 훑는다. 매 실행 0에서 시작하면 배송중이 상한보다 많을 때 앞쪽 {@value #MAX_PER_RUN}건만 계속 조회되고
 * 뒤쪽은 영영 차례가 오지 않는다(굶주림) — 한 실행 안의 커서 전진만으로는 막지 못하는 지점이다.
 *
 * <p><b>단일 인스턴스 전제</b>: 커서는 인스턴스 필드이므로 여러 인스턴스가 뜨면 각자 다른 위치를 들고 돈다(중복 조회·건너뜀).
 * 분산 락이 없는 기존 스케줄러 9종과 같은 전제이며(D-210 §8), 다중 인스턴스 도입 시 함께 재검토한다. 재기동하면 0부터 다시 시작한다
 * (전이는 멱등이라 중복 조회 비용만 든다).
 *
 * <p><b>부분 실패 격리</b>: 건 단위 try/catch로 한 건 실패가 배치 전체를 중단시키지 않는다. {@link Exception}만 흡수하고 {@link Error}는
 * 전파한다.
 *
 * <p><b>발화 억제(테스트·운영 킬스위치)</b>: {@code zslab.delivery.auto-complete.enabled=false}면 본 빈이 생성되지 않아 {@code @Scheduled}가
 * 비활성된다(기본 활성·기존 스케줄러 킬스위치 관례). 수동 배송완료 경로(관리자·셀러 API)는 이 스위치와 무관하게 그대로 동작한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zslab.delivery.auto-complete.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DeliveryAutoCompleteScheduler {

    /** 1회 조회 페이지 크기(외부 조회 호출 묶음 단위). */
    private static final int PAGE_SIZE = 100;

    /** 1회 실행에서 훑는 배송 상한(외부 조회 호출 수·1틱 부하 제어). 남은 건은 다음 실행이 {@link #cursor}부터 이어받는다. */
    private static final int MAX_PER_RUN = 1000;

    /** 자동 배송완료 배치 실행 간격(1시간). 직전 실행 종료 후 고정 지연. */
    private static final long FIXED_DELAY_MS = 60 * 60 * 1000L;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryTracker deliveryTracker;
    private final DeliveryAutoCompleteService deliveryAutoCompleteService;

    /**
     * 다음 실행이 시작할 배송 id(이 값보다 큰 id부터 조회). 실행 사이에 유지되며 끝에 도달하면 0으로 되돌아간다.
     * {@code @Scheduled}는 한 번에 하나씩만 실행되므로 동시 접근이 없다(단일 인스턴스 전제).
     */
    private long cursor = 0L;

    /**
     * 배송중 배송을 커서로 순회하며 배달 완료로 확인된 건을 배송완료 처리한다. 후보 조회·배송 조회·건별 전이는 서로 다른 트랜잭션에서
     * 수행된다(배송 조회는 트랜잭션 밖).
     */
    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void completeBatch() {
        String schedulerRunId = UUID.randomUUID().toString();
        long startCursor = cursor;
        int scanned = 0;
        int completed = 0;
        int skipped = 0;
        int failed = 0;

        while (scanned < MAX_PER_RUN) {
            List<DeliveryTrackingCandidate> page = deliveryRepository.findAutoCompleteCandidates(
                    DeliveryDirection.OUTBOUND, DeliveryStatus.SHIPPING, cursor, PageRequest.of(0, PAGE_SIZE));
            if (page.isEmpty()) {
                // 커서 뒤로 남은 배송이 없다 = 끝에 도달 → 다음 실행은 처음부터.
                cursor = 0L;
                break;
            }
            for (DeliveryTrackingCandidate candidate : page) {
                cursor = candidate.deliveryId();
                scanned++;
                try {
                    DeliveryTrackingStatus tracked = deliveryTracker.track(
                            candidate.carrier(), candidate.trackingNo(), candidate.shippedAt());
                    if (tracked != DeliveryTrackingStatus.DELIVERED) {
                        skipped++;
                        continue;
                    }
                    if (deliveryAutoCompleteService.completeOne(
                            candidate.deliveryId(), candidate.carrier(), candidate.trackingNo())) {
                        completed++;
                    } else {
                        skipped++;
                    }
                } catch (Exception exception) {
                    // Error(OOM 등)는 흡수하지 않고 전파한다. RuntimeException 1건 실패는 격리 후 다음 건을 계속 처리한다.
                    failed++;
                    log.error("[DeliveryAutoComplete] schedulerRunId={} 처리 실패 deliveryId={} — 격리 후 진행",
                            schedulerRunId, candidate.deliveryId(), exception);
                }
                if (scanned >= MAX_PER_RUN) {
                    break;
                }
            }
            if (page.size() < PAGE_SIZE) {
                // 마지막 페이지까지 훑었다 → 다음 실행은 처음부터.
                cursor = 0L;
                break;
            }
        }

        if (scanned == 0) {
            log.debug("[DeliveryAutoComplete] schedulerRunId={} 커서 {} 뒤로 배송중 배송 없음 → 다음 실행은 처음부터",
                    schedulerRunId, startCursor);
            return;
        }
        log.info("[DeliveryAutoComplete] schedulerRunId={} 배치 완료 조회={} 배송완료={} skip={} 실패={} 커서 {}→{}",
                schedulerRunId, scanned, completed, skipped, failed, startCursor, cursor);
    }
}
