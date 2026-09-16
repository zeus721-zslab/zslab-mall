package com.zslab.mall.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 미결제 종료(PAYMENT_EXPIRED) 주문 hard delete 배치 지표(FE-12c-2·EventMetricsRecorder 패턴 동형). 삭제 실패·이연 케이스를
 * {@code reason} 태그로 구분해 계측한다(저카디널리티 유지). 삭제 성공은 로그로 갈음하고 별도 카운터를 두지 않는다(과잉개발 회피).
 *
 * <p><b>reason 태그(고정 집합)</b>: {@link #REASON_RESTRICT_VIOLATION}(자식/손자 FK RESTRICT로 삭제 실패·데이터 이상).
 * 구 reserved_unreleased(재고 미해제·삭제 이연)는 Track 78 D-167 보충2(γ)로 판정 자체가 폐기돼 제거했다. 그 외 예외는 스케줄러 격리 로그로 남긴다.
 */
@Component
@RequiredArgsConstructor
public class ExpiredOrderCleanupMetrics {

    /** 자식·손자 FK RESTRICT로 삭제가 차단된 경우(정상 흐름상 미발생·데이터 이상 신호). */
    public static final String REASON_RESTRICT_VIOLATION = "restrict_violation";

    private static final String METRIC_DELETION_FAILED = "zslab.order.expired.deletion_failed";

    private final MeterRegistry registry;

    /**
     * 미결제 종료 주문 삭제 실패·이연을 계측한다.
     *
     * @param reason 사유 태그(위 REASON_* 상수 중 하나)
     */
    public void recordDeletionFailed(String reason) {
        Counter.builder(METRIC_DELETION_FAILED)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
