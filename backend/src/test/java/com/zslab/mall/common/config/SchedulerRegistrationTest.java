package com.zslab.mall.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.delivery.adapter.DeliveryTracker;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.delivery.scheduler.DeliveryAutoCompleteScheduler;
import com.zslab.mall.delivery.service.DeliveryAutoCompleteService;
import com.zslab.mall.grade.scheduler.GradeRecalculationScheduler;
import com.zslab.mall.grade.service.GradeRecalculationBatchService;
import com.zslab.mall.grade.service.GradeRecalculationResult;
import com.zslab.mall.settlement.scheduler.SettlementMonthlyCreationScheduler;
import com.zslab.mall.settlement.service.SettlementBatchResult;
import com.zslab.mall.settlement.service.SettlementCreationService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;

/**
 * Track 96-6 스케줄러 2종(월 정산 자동 생성·등급 재산정)과 Track 99 자동 배송완료의 활성화 등록 검증(외부 검토 반영). DB 없는 {@link ApplicationContextRunner}
 * 경량 컨텍스트로 킬스위치 3상태(미지정·true·false)와 {@code @Scheduled(fixedDelay=24h)} 실제 등록을 단언한다. 통합 테스트는
 * {@code AbstractIntegrationTest}가 킬스위치를 전역으로 내려 빈 부재만 검증하므로, "운영 기본값(미지정)에서 실제로 등록되는가"는 본
 * 테스트가 유일한 증거다. 의존 서비스는 Mockito mock이라 {@code @EnableScheduling}으로 즉시 발화해도 DB 부작용이 없다.
 */
class SchedulerRegistrationTest {

    private static final Duration EXPECTED_FIXED_DELAY = Duration.ofHours(24);
    private static final String SETTLEMENT_KEY = "zslab.settlement.monthly-creation.enabled";
    private static final String GRADE_KEY = "zslab.grade.recalculation.enabled";
    private static final String DELIVERY_KEY = "zslab.delivery.auto-complete.enabled";
    private static final Duration EXPECTED_DELIVERY_FIXED_DELAY = Duration.ofHours(1);

    @Configuration
    static class MockDependencies {
        @Bean
        SettlementCreationService settlementCreationService() {
            SettlementCreationService mock = Mockito.mock(SettlementCreationService.class);
            Mockito.when(mock.createMonthlySettlements(ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                    .thenReturn(new SettlementBatchResult(LocalDateTime.MIN, LocalDateTime.MIN, List.of()));
            return mock;
        }

        @Bean
        GradeRecalculationBatchService gradeRecalculationBatchService() {
            GradeRecalculationBatchService mock = Mockito.mock(GradeRecalculationBatchService.class);
            Mockito.when(mock.recalculateAll()).thenReturn(new GradeRecalculationResult(0, 0, 0));
            return mock;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockDependencies.class, SchedulingConfig.class,
                    SettlementMonthlyCreationScheduler.class, GradeRecalculationScheduler.class);

    @Test
    @DisplayName("킬스위치 미지정(운영 기본값): 두 스케줄러 빈 존재 + @Scheduled fixedDelay 24h 각 1건 등록")
    void keysMissing_beansPresentAndScheduled() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SettlementMonthlyCreationScheduler.class);
            assertThat(context).hasSingleBean(GradeRecalculationScheduler.class);

            List<Duration> fixedDelays = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().stream()
                    .map(scheduledTask -> scheduledTask.getTask())
                    .filter(FixedDelayTask.class::isInstance)
                    .map(task -> ((FixedDelayTask) task).getIntervalDuration())
                    .toList();
            assertThat(fixedDelays).hasSize(2).allMatch(EXPECTED_FIXED_DELAY::equals);
        });
    }

    @Test
    @DisplayName("킬스위치 true: 두 스케줄러 빈 존재")
    void keysTrue_beansPresent() {
        runner.withPropertyValues(SETTLEMENT_KEY + "=true", GRADE_KEY + "=true").run(context -> {
            assertThat(context).hasSingleBean(SettlementMonthlyCreationScheduler.class);
            assertThat(context).hasSingleBean(GradeRecalculationScheduler.class);
        });
    }

    @Test
    @DisplayName("킬스위치 false: 두 스케줄러 빈 없음·@Scheduled 등록 0")
    void keysFalse_beansAbsent() {
        runner.withPropertyValues(SETTLEMENT_KEY + "=false", GRADE_KEY + "=false").run(context -> {
            assertThat(context).doesNotHaveBean(SettlementMonthlyCreationScheduler.class);
            assertThat(context).doesNotHaveBean(GradeRecalculationScheduler.class);
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
        });
    }

    // ---------- Track 99 자동 배송완료(주기가 1시간이라 위 24h 묶음과 분리한다) ----------

    @Configuration
    static class DeliveryMockDependencies {
        @Bean
        DeliveryRepository deliveryRepository() {
            return Mockito.mock(DeliveryRepository.class);
        }

        @Bean
        DeliveryTracker deliveryTracker() {
            return Mockito.mock(DeliveryTracker.class);
        }

        @Bean
        DeliveryAutoCompleteService deliveryAutoCompleteService() {
            return Mockito.mock(DeliveryAutoCompleteService.class);
        }
    }

    private final ApplicationContextRunner deliveryRunner = new ApplicationContextRunner()
            .withUserConfiguration(DeliveryMockDependencies.class, SchedulingConfig.class,
                    DeliveryAutoCompleteScheduler.class);

    @Test
    @DisplayName("자동 배송완료 킬스위치 미지정(운영 기본값): 빈 존재 + @Scheduled fixedDelay 1h 1건 등록")
    void deliveryKeyMissing_beanPresentAndScheduled() {
        deliveryRunner.run(context -> {
            assertThat(context).hasSingleBean(DeliveryAutoCompleteScheduler.class);
            List<Duration> fixedDelays = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().stream()
                    .map(scheduledTask -> scheduledTask.getTask())
                    .filter(FixedDelayTask.class::isInstance)
                    .map(task -> ((FixedDelayTask) task).getIntervalDuration())
                    .toList();
            assertThat(fixedDelays).containsExactly(EXPECTED_DELIVERY_FIXED_DELAY);
        });
    }

    @Test
    @DisplayName("자동 배송완료 킬스위치 true: 빈 존재")
    void deliveryKeyTrue_beanPresent() {
        deliveryRunner.withPropertyValues(DELIVERY_KEY + "=true")
                .run(context -> assertThat(context).hasSingleBean(DeliveryAutoCompleteScheduler.class));
    }

    @Test
    @DisplayName("자동 배송완료 킬스위치 false: 빈 없음·@Scheduled 등록 0")
    void deliveryKeyFalse_beanAbsent() {
        deliveryRunner.withPropertyValues(DELIVERY_KEY + "=false").run(context -> {
            assertThat(context).doesNotHaveBean(DeliveryAutoCompleteScheduler.class);
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
        });
    }

    @Test
    @DisplayName("킬스위치 독립: 정산만 false → 정산 빈 없음·등급 빈 존재(반대도 동일)")
    void keysIndependent() {
        runner.withPropertyValues(SETTLEMENT_KEY + "=false").run(context -> {
            assertThat(context).doesNotHaveBean(SettlementMonthlyCreationScheduler.class);
            assertThat(context).hasSingleBean(GradeRecalculationScheduler.class);
        });
        runner.withPropertyValues(GRADE_KEY + "=false").run(context -> {
            assertThat(context).hasSingleBean(SettlementMonthlyCreationScheduler.class);
            assertThat(context).doesNotHaveBean(GradeRecalculationScheduler.class);
        });
    }
}
