package com.zslab.mall.delivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Track 99 D-210 배송 조회 구현체 선택 프로퍼티 검증({@code ExternalServiceSelectionTest} 동형). DB 없는
 * {@link ApplicationContextRunner}로 {@code zslab.delivery.tracker}의 미지정·{@code mock}·mock 외 값에서 Mock 빈 존재/부재를 단언하고,
 * Mock 어댑터의 경과 일수 판정({@code zslab.delivery.tracker-mock-days})도 함께 본다.
 */
class DeliveryTrackerSelectionTest {

    private static final String TRACKER_KEY = "zslab.delivery.tracker";
    private static final String MOCK_DAYS_KEY = "zslab.delivery.tracker-mock-days";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockDeliveryTracker.class);

    @Test
    @DisplayName("키 미지정(기본값): Mock 배송 조회 빈 존재")
    void keyMissing_mockPresent() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DeliveryTracker.class);
            assertThat(context).hasSingleBean(MockDeliveryTracker.class);
        });
    }

    @Test
    @DisplayName("mock 명시: 기본값과 동일하게 Mock 빈 존재")
    void keyMock_mockPresent() {
        runner.withPropertyValues(TRACKER_KEY + "=mock").run(context -> {
            assertThat(context).hasSingleBean(MockDeliveryTracker.class);
        });
    }

    @Test
    @DisplayName("mock 외 값: Mock 빈 부재(실 구현체가 없으면 주입 지점에서 기동 실패 — 조용한 폴백 없음)")
    void keyReal_mockAbsent() {
        runner.withPropertyValues(TRACKER_KEY + "=some-carrier").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DeliveryTracker.class);
            assertThat(context).doesNotHaveBean(MockDeliveryTracker.class);
        });
    }

    @Test
    @DisplayName("Mock 판정: 발송 후 N일 경과면 DELIVERED · 미경과면 IN_TRANSIT · 송장/발송시각 없으면 UNKNOWN")
    void mockTrackerDecidesByElapsedDays() {
        runner.withPropertyValues(MOCK_DAYS_KEY + "=2").run(context -> {
            DeliveryTracker tracker = context.getBean(DeliveryTracker.class);
            LocalDateTime now = LocalDateTime.now();
            assertThat(tracker.track(com.zslab.mall.delivery.enums.DeliveryCarrier.CJ, "TRK-1", now.minusDays(3)))
                    .isEqualTo(DeliveryTrackingStatus.DELIVERED);
            assertThat(tracker.track(com.zslab.mall.delivery.enums.DeliveryCarrier.CJ, "TRK-2", now.minusDays(1)))
                    .isEqualTo(DeliveryTrackingStatus.IN_TRANSIT);
            assertThat(tracker.track(com.zslab.mall.delivery.enums.DeliveryCarrier.CJ, null, now.minusDays(3)))
                    .isEqualTo(DeliveryTrackingStatus.UNKNOWN);
            assertThat(tracker.track(com.zslab.mall.delivery.enums.DeliveryCarrier.CJ, "TRK-3", null))
                    .isEqualTo(DeliveryTrackingStatus.UNKNOWN);
        });
    }

    @Test
    @DisplayName("mock-days=0: 발송 즉시 DELIVERED")
    void mockDaysZero() {
        runner.withPropertyValues(MOCK_DAYS_KEY + "=0").run(context -> {
            DeliveryTracker tracker = context.getBean(DeliveryTracker.class);
            assertThat(tracker.track(com.zslab.mall.delivery.enums.DeliveryCarrier.CJ, "TRK-1", LocalDateTime.now()))
                    .isEqualTo(DeliveryTrackingStatus.DELIVERED);
        });
    }
}
