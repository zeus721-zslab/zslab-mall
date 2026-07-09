package com.zslab.mall.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 미결제 종료(PAYMENT_EXPIRED) 주문 hard delete 배치 지표(FE-12c-2·EventMetricsRecorder 패턴 동형). 삭제 실패·이연 케이스를
 * {@code reason} 태그로 구분해 계측한다(저카디널리티 유지). 삭제 성공은 로그로 갈음하고 별도 카운터를 두지 않는다(과잉개발 회피).
 *
 * <p><b>reason 태그(고정 집합)</b>: {@link #REASON_RESERVED_UNRELEASED}(재고 미해제·삭제 이연)·
 * {@link #REASON_RESTRICT_VIOLATION}(자식/손자 FK RESTRICT로 삭제 차단·데이터 이상). 그 외 예외는 스케줄러 격리 로그로 갈음한다.
 */
@Component
@RequiredArgsConstructor
public class ExpiredOrderCleanupMetrics {

    /** 재고 미해제(reserved>0)로 삭제를 이연한 경우. */
    public static final String REASON_RESERVED_UNRELEASED = "reserved_unreleased";
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
